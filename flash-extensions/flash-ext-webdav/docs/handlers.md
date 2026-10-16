# Handlers

A mount is a class that extends the handler of an engine. The engine decides where the files are;
the class decides what each request sees and may do.

| Class | Engine |
|---|---|
| `StandaloneNioDavHandler` | A directory of any `java.nio.file` file system (the local disk, a zip file, an in-memory one) that one process serves |
| `ClusteredNioDavHandler` | The same, served by several processes: see [clustered.md](clustered.md) |
| `DavHandler` | The base of every engine. Extend it to write one: see [engines.md](engines.md) |

## Directories

`StandaloneNioDavHandler` and `ClusteredNioDavHandler`, in `dev.relism.flash.ext.webdav.nio`, are the two kinds of
`NioDavHandler`, and the only ones: it cannot be extended directly. They differ only in where the state of a directory lives (the bytes in
use, uploads in progress, locks): in the memory of the one process that serves it, or in `.dav`
under its root, where every process reads it.

```java
@Dav("/files/**")
final class Files extends StandaloneNioDavHandler {
    @Override protected Path root(Request req) { return Path.of("/srv/files"); }
}
```

`root(req)` is called once per request and says which directory it sees. A directory that does not
exist is created. Different requests may see different directories: a space per user, per team, per
tenant.

| Aspect | Behavior |
|---|---|
| `PUT` | Written into a file of its own (beside the target, or in `.dav/uploads` when clustered), then moved over the target (atomically where the file system can), so a broken upload leaves the old content. Dead properties of a replaced file are kept |
| `COPY`, `MOVE` | `Files.copy` of each resource, `Files.move` of a whole tree |
| Symbolic links | Followed while they stay inside the root. One that leads out is `403`, and left out of listings. One that leads to nothing is treated as nothing there |
| Dead properties | Extended attributes when the file store has them (`UserDefinedFileAttributeView`), otherwise in memory |
| Free space | The file store's usable space |
| Bytes in use | The sizes of the files below the root, links not followed, `.dav` left out |
| `.dav` under the root | With `StandaloneNioDavHandler`, a name like any other. With `ClusteredNioDavHandler`, the state: clients do not reach it unless `stateVisible()` returns `true` (see [clustered.md](clustered.md#dav)) |
| Names | Stored as sent. A name the JVM cannot encode is `403`: non-ASCII names need the JVM's file name encoding (`sun.jnu.encoding`, set by the locale) to be UTF-8 |

## What the caller may do

Every handler can override these two methods. `writable` is called once per request; `quota` at most
once, and only when the request needs it.

| Method | Default | Effect |
|---|---|---|
| `long quota(Request req)` | `Long.MAX_VALUE`: no limit | The bytes the storage may hold. See [quota.md](quota.md) |
| `boolean writable(Request req)` | `true` | `false` makes the mount read-only for this request |

A read-only request is served `OPTIONS`, `GET`, `HEAD` and `PROPFIND`; `OPTIONS` answers `DAV: 1`,
`Allow` lists those four, `supportedlock` is empty, and every other method is `405` with `Allow`.
Finder mounts such a share read-only. A storage that cannot write ([engines.md](engines.md)) is
read-only for every request.

A space per user, with a quota by role:

```java
@Dav("/drive/**")
@Authenticated
final class Drive extends StandaloneNioDavHandler {
    private final Path disk;
    private final Quotas quotas;

    Drive(Settings settings, Quotas quotas) {
        this.disk = settings.driveRoot();
        this.quotas = quotas;
    }

    @Override protected Path root(Request req) {
        return disk.resolve(me().id());
    }

    @Override protected long quota(Request req) {
        return quotas.forRole(me().role());
    }

    @Override protected boolean writable(Request req) {
        return !me().suspended();
    }

    private static Account me() {
        return SecurityIdentity.current().user(Account.class);
    }
}
```

`SecurityIdentity.user` resolves the account once per request, through the `UserResolver` given to
`SecurityModule.users`.

## Each operation

Each time the handler uses the storage it calls one of these methods, with the request first. The
default performs the operation. Override one to refuse it, log it or change it, and call `super` to
perform it.

| Method | Called for |
|---|---|
| `Optional<DavNode> stat(Request, DavPath)` | Every lookup: the request's resource, its parent, a `Destination`, a resource of the `If` header |
| `List<DavNode> list(Request, DavPath)` | `PROPFIND` with `Depth: 1`, `GET` on a collection, each level of a deep `DELETE` or `COPY` |
| `InputStream read(Request, DavPath, long offset, long length)` | The body of a `GET` |
| `void write(Request, DavPath, InputStream, OptionalLong length)` | `PUT`, and the empty file a `LOCK` on a free path creates |
| `void mkdir(Request, DavPath)` | `MKCOL` |
| `void delete(Request, DavPath)` | Each resource a `DELETE` removes, members first, and a destination `COPY` or `MOVE` replaces |
| `void copy(Request, DavPath from, DavPath to)` | Each resource a `COPY` copies, a collection before its members |
| `void move(Request, DavPath from, DavPath to)` | A `MOVE`, once for the whole tree |
| `Map<QName, String> properties(Request, DavPath)` | Reading dead properties: `PROPFIND`, `PROPPATCH`, `COPY` |
| `void properties(Request, DavPath, Map<QName, String>)` | `PROPPATCH`, and the properties a `COPY` carries |

An exception becomes the answer's status, as listed in [engines.md](engines.md#errors).
`HttpException.forbidden()` refuses with `403`. When a `DELETE` or `COPY` touches several
resources, one that fails is reported in a `207` and the others go on; when only the resource the
request names fails, the answer is its status.

```java
@Dav("/files/**")
@Authenticated
final class Files extends StandaloneNioDavHandler {
    private final Audit audit;

    Files(Audit audit) { this.audit = audit; }

    @Override protected Path root(Request req) { return Path.of("/srv/files"); }

    @Override protected void delete(Request req, DavPath path) throws IOException {
        if (path.segments().size() == 1 && !SecurityIdentity.current().hasRole("admin"))
            throw HttpException.forbidden("top-level folders are kept");
        super.delete(req, path);
        audit.deleted(SecurityIdentity.current().principal().name(), path.toString());
    }

    @Override protected InputStream read(Request req, DavPath file, long offset, long length) throws IOException {
        audit.downloaded(SecurityIdentity.current().principal().name(), file.toString());
        return super.read(req, file, offset, length);
    }
}
```

To hide resources, override both `stat` and `list`: `stat` returning `Optional.empty()` makes a path
`404` for every method, and `list` leaves it out of listings.

## Locks and spaces

Locks, the bytes in use and dead properties kept in memory belong to the storage, not to the URL:
two mounts that reach the same directory share them, and two users who see different directories do
not. Several processes share them when the directory is [clustered](clustered.md).
