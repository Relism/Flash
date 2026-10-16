# Engines

An engine serves a mount from somewhere other than a directory: an object store, a database, a
repository. It has two parts:

- a `DavStorage`: what a resource is, and how bytes move;
- a handler, which extends `DavHandler` and opens that storage for each request.

`NioDavHandler`, with its two kinds `StandaloneNioDavHandler` and `ClusteredNioDavHandler` in
`dev.relism.flash.ext.webdav.nio`, is the engine this module ships, built the same way. The handler speaks the protocol
(methods, locks, the `If` header, ranges, properties, the quota), so an engine never sees XML or
headers.

## The handler

`DavHandler` asks one thing of an engine:

```java
protected abstract DavStorage open(Request req) throws IOException;
```

It is called once per request. Make it `final` and give applications a method in the engine's own
terms, as `NioDavHandler` does with `Path root(Request)`:

```java
public abstract class BlobDavHandler extends DavHandler {
    private final Blobs blobs;

    protected BlobDavHandler(Blobs blobs) { this.blobs = blobs; }

    /** The key prefix this request sees, such as "users/42/". */
    protected abstract String prefix(Request req);

    @Override protected final DavStorage open(Request req) {
        return new BlobStorage(blobs, prefix(req));
    }
}
```

An application then writes `final class Drive extends BlobDavHandler` and overrides `prefix`, and
`quota`, `writable` and the operations' methods as with any handler ([handlers.md](handlers.md)).

## DavStorage

```java
public interface DavStorage {
    Optional<DavNode> stat(DavPath path) throws IOException;
    List<DavNode> list(DavPath collection) throws IOException;
    InputStream read(DavPath file, long offset, long length) throws IOException;
    default long usage(DavPath path) throws IOException { ... }
    default OptionalLong free() throws IOException { ... }

    interface Writable extends DavStorage { ... }
    interface Properties extends DavStorage { ... }
}
```

| Method | Contract |
|---|---|
| `stat(path)` | The resource, or empty when there is none. The root (`DavPath` with no segments) is a collection |
| `list(collection)` | The members of a collection, each named by its last segment |
| `read(file, offset, length)` | Exactly `length` bytes from `offset`, then the end of the stream. The handler closes it |
| `usage(path)` | The bytes of the files at `path` and below. Default: the sum over `list`, level by level. Override it when the store can answer faster |
| `free()` | The bytes the store can still take, when it knows. Default: empty |

What a storage can do is in its type:

| Implements | The mount |
|---|---|
| `DavStorage` only | Is read-only for every request |
| `DavStorage.Writable` | Serves `PUT`, `MKCOL`, `DELETE`, `COPY`, `MOVE`, `PROPPATCH`, `LOCK`, `UNLOCK`, unless `writable(req)` is `false` |
| `DavStorage.Properties` | Keeps dead properties where the storage puts them. Without it they are kept in memory, logged at `INFO` the first time |

### Writable

| Method | Contract |
|---|---|
| `write(file, body, length)` | Creates or replaces a file. `length` is the body's length when the client sent it. The body can fail midway (the client left, the quota was reached): leave the previous content whole when you can. Read the body to its end, on the calling thread, before the new content shows: at its end the handler checks the request again and holds the storage's state until `write` returns |
| `mkdir(collection)` | Creates a collection whose parent exists |
| `delete(path)` | Removes a file or an empty collection. The handler deletes members first |
| `copy(from, to)` | Copies one resource to a free path: a file's content, or an empty collection. Default: `read` then `write`, or `mkdir` |
| `move(from, to)` | Moves a resource and everything below it to a free path. Default: `copy` each resource, its dead properties when the storage keeps them, then `delete` |

### Properties

| Method | Contract |
|---|---|
| `Map<QName, String> properties(path)` | The properties clients set on a resource; empty when none |
| `properties(path, map)` | Replaces them all |

Each value is the property element's content as XML, with every namespace it uses declared in it.
Store it as it is.

### Equality

The handler keeps locks, the bytes in use and in-memory properties by the storage's `equals`: two
storages that show the same resources must be equal, and two that show different ones must not. A
record over the store and the location gets this right, as `BlobStorage` below does with its prefix.

They live in the memory of the process, so one process serves a storage: two instances of the app
behind one address would each count their own bytes and hold their own locks. Only `ClusteredNioDavHandler`
keeps them where several processes read them ([clustered.md](clustered.md)).

### Threads

One storage serves concurrent requests. Keep it immutable, or safe to share.

### Errors

Methods throw `IOException`. The handler answers with a status:

| Exception | Status |
|---|---|
| `HttpException` | Its own |
| `NoSuchFileException`, `FileAlreadyExistsException`, `DirectoryNotEmptyException` | `409` |
| `AccessDeniedException` | `403` |
| `UnsupportedOperationException` | `405` |
| An `IOException` whose message says `No space left on device` (`not enough space on the disk` on Windows) | `507` with `<D:sufficient-disk-space/>` |
| An `IOException` whose message says `Disk quota exceeded` | `507` with `<D:quota-not-exceeded/>` |
| Any other | `500`, through the app's error handler |

## An engine over a blob store

```java
/** The application's store: keys with "/" in them; a folder is a key that ends in "/". */
public interface Blobs {
    record Entry(String key, long size, Instant modified) {}
    Optional<Entry> get(String key);
    List<Entry> children(String prefix);   // the keys right below prefix, folders included
    InputStream open(String key, long offset, long length);
    void put(String key, InputStream body);
    void delete(String key);
}

record BlobStorage(Blobs blobs, String prefix) implements DavStorage.Writable {

    @Override public Optional<DavNode> stat(DavPath path) {
        if (path.isRoot()) return Optional.of(DavNode.collection("", Instant.EPOCH));
        return blobs.get(key(path)).map(entry -> DavNode.file(path.name(), entry.size(), entry.modified()))
                .or(() -> blobs.get(folder(path)).map(entry -> DavNode.collection(path.name(), entry.modified())));
    }

    @Override public List<DavNode> list(DavPath collection) {
        return blobs.children(folder(collection)).stream().map(entry -> {
            String key = entry.key();
            boolean folder = key.endsWith("/");
            String name = key.substring(folder(collection).length(), key.length() - (folder ? 1 : 0));
            return folder ? DavNode.collection(name, entry.modified()) : DavNode.file(name, entry.size(), entry.modified());
        }).toList();
    }

    @Override public InputStream read(DavPath file, long offset, long length) {
        return blobs.open(key(file), offset, length);
    }

    @Override public void write(DavPath file, InputStream body, OptionalLong length) {
        blobs.put(key(file), body);
    }

    @Override public void mkdir(DavPath collection) {
        blobs.put(folder(collection), InputStream.nullInputStream());
    }

    @Override public void delete(DavPath path) {
        blobs.delete(blobs.get(key(path)).isPresent() ? key(path) : folder(path));
    }

    private String key(DavPath path) {
        return prefix + String.join("/", path.segments());
    }

    private String folder(DavPath path) {
        return path.isRoot() ? prefix : key(path) + "/";
    }
}
```

`copy` and `move` are the defaults: a store that copies on its side overrides `copy`, and one that
renames overrides `move`.

## DavPath

A path below the mount, decoded from the URL, normalized (`.` and `..` resolved) and never above the
mount. Each segment is a name: not empty, not `.` or `..`, without `/` or NUL. Names keep the form
the client sent: Finder's decomposed Unicode stays decomposed.

| Method | Returns |
|---|---|
| `segments()` | The names from the mount down |
| `name()` | The last name; `""` for the mount |
| `isRoot()` | Whether this is the mount itself |
| `parent()`, `child(name)` | The enclosing collection (`null` for the mount), a member |
| `contains(other)` | Whether `other` is this path or below it |
| `toString()` | `/a/b`, decoded; `/` for the mount |

## DavNode

```java
public record DavNode(String name, boolean collection, long length, Instant modified, Instant created,
                      String etag, String contentType) {}
```

`DavNode.file(name, length, modified)` and `DavNode.collection(name, modified)` fill in the rest: the
creation time as the modification time, a strong entity tag from the length and the modification
time, and a file's media type from its extension (`application/octet-stream` when unknown). The tag
must change whenever the content does.
