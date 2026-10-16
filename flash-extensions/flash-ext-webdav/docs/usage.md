# Usage

## Mounts

A mount is one handler answering every method on a path and everything below it. Declare it on the
class with `@Dav`, or with `app.any`:

```java
@Dav("/files/**")
final class Files extends StandaloneNioDavHandler {
    @Override protected Path root(Request req) { return Path.of("/srv/files"); }
}

app.any("/files/**", new Files());   // the same route, without scan
```

What the handler can decide and override is in [handlers.md](handlers.md).

`@Dav` is a routing annotation for every method (it carries `@AnyMethod`), so `scan` builds the
handler with its services. The path must end in `/**`; the mount is what comes before it, and
`/files/**` also answers `/files` itself. Mounts work under `app.mount` prefixes.

The handler is a `RequestHandler`: middleware, security annotations, `@Transactional` and `@Limit`
apply to it as to any route, for every method.

## DavModule

```java
app.install(new DavModule().maxLockTimeout(Duration.ofMinutes(30)));
```

| Method | Default | Effect |
|---|---|---|
| `maxLockTimeout(Duration)` | 1 hour | The longest lock granted. Less than 600 seconds throws `IllegalArgumentException` |

The module holds what every mount shares (the locks and bytes in use of each storage, dead properties
kept in memory), and checks each `DavHandler` route at boot:

| Problem | Boot failure |
|---|---|
| The route is for one method | `<Handler> is a DavHandler: route it for every method, with @Dav or app.any` |
| The path does not end in `/**` | `<Handler> at <path>: a WebDAV mount covers a subtree; route it at <path>/**` |
| `DavModule` is not installed | `<Handler> is a DavHandler: install new DavModule()` |

It also answers `OPTIONS` on `/` and on every path above a mount, for Windows: see
[security.md](security.md#options-above-a-mount).

## Methods

`OPTIONS` on a mount answers `200` with `DAV: 1, 2`, `MS-Author-Via: DAV` and `Allow` listing the
methods this request is served; a read-only request (see
[handlers.md](handlers.md#what-the-caller-may-do)) gets `DAV: 1` and the four read methods. A method
of the list the request is not served, and any method of `HttpMethod` outside it, is `405` with
`Allow`; any other token is `501`.

| Method | Answers |
|---|---|
| `GET`, `HEAD` | A file: `200` with `Content-Length`, `ETag`, `Last-Modified` (GMT), `Accept-Ranges: bytes`. A collection: an HTML list of its members. `404` when absent |
| `PUT` | `201` created, `204` replaced, with the new `ETag`. `409` without a parent collection, `405` on a collection, `400` with `Content-Range`, `507` past the [quota](quota.md) |
| `DELETE` | `204`, members first. `207` listing the members that could not be deleted (they stay, with their parents); the status of the resource itself when only it failed. `403` on the mount itself |
| `MKCOL` | `201`. `405` when the path exists, `409` without a parent collection, `415` with a body |
| `COPY`, `MOVE` | `201` created, `204` replaced. See below |
| `PROPFIND`, `PROPPATCH` | `207`. See [properties.md](properties.md) |
| `LOCK`, `UNLOCK` | See [locking.md](locking.md) |

Conditional requests follow RFC 9110 for `GET`, `HEAD` and `PUT`: `If-Match` and
`If-Unmodified-Since` give `412`; `If-None-Match` and `If-Modified-Since` give `304` on a read and
`412` on a write. One `Range` (`bytes=a-b`, `bytes=a-`, `bytes=-n`) gives `206` with
`Content-Range`, or `416` when it starts past the end; several ranges give the whole file with `200`,
and so does an `If-Range` that no longer matches.

A `PUT` is checked when it starts and again once its body has arrived, before the new content
replaces the old: a lock taken or a change made meanwhile makes it `423` or `412`, and the old content
stays.

A `PUT` body may be chunked. When it is, and `X-Expected-Entity-Length` is present (Finder sends it),
that value is passed to the storage as the length. A body larger than the server's
[`maxBodySize`](../../../flash/docs/config.md#limits) (10 MiB by default) is refused before the
handler runs, so a server for large files raises it.

### COPY and MOVE

| Condition | Answer |
|---|---|
| No `Destination` | `400` |
| `Destination` on another host, or outside this mount | `502` |
| `Destination` is the source, or inside it | `403` |
| `Overwrite: F` and the destination exists | `412` |
| The destination's parent collection is missing | `409` |
| `Depth` other than `0` or `infinity`; `MOVE` of a collection with `Depth: 0` | `400` |

`COPY` past the [quota](quota.md) is `507`. A failure of the destination alone is its own status;
failures below it are a `207`.

`Destination` is an absolute URL whose host must equal the request's `Host`, or an absolute path.
Without `Depth`, a copy is deep; `Depth: 0` copies a collection without its members. A destination
that exists is deleted first. Dead properties are copied and moved with their resources; locks are
not, and a moved resource's locks are released.
