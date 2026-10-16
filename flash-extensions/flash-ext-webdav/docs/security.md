# Security

## Authentication

A mount is a route like any other: annotate the handler (`@Authenticated`, `@RolesAllowed`) and
install an `AuthenticationMechanism`. Operating systems mount WebDAV with a user name and password,
which is [flash-ext-security-basic](../../flash-ext-security-basic/README.md):

```java
app.install(new SecurityModule(), new BasicAuth(accounts::byUsername), new DavModule());

@Dav("/files/**")
@Authenticated
final class Files extends StandaloneNioDavHandler { ... }
```

The policy applies to every method of the mount, `OPTIONS` included. Serve Basic over TLS only.
What an authenticated caller sees and may do is decided per request by the handler: its own space,
its quota, read-only access, a refusal of a single operation ([handlers.md](handlers.md)).

## OPTIONS above a mount

Windows sends `OPTIONS /` before it uses a share at any path, and gives up on `404` or `401`.
`DavModule` therefore answers `OPTIONS` on `/` and on every path above a mount (for `/apps/files/**`:
`/` and `/apps`) with `200`, `DAV: 1, 2`, `MS-Author-Via: DAV` and `Allow: OPTIONS`, without
authentication. The answer names no resource and reads nothing.

It does not answer:

- a CORS preflight (`Access-Control-Request-Method` present), which is left to `Cors`;
- a path where the app has its own `OPTIONS` route;
- any other path or method.

It is a middleware added with `use` when the module is installed, so a `use` that authenticates every
request must come after `install(new DavModule())`.

## Paths

Handlers and storages receive a `DavPath`, never a string from the request:

- percent-escapes are decoded once, as UTF-8; malformed escapes, invalid UTF-8, an encoded `/` or a
  NUL in a name are `400`;
- `.` and `..` are resolved, and a path that would leave the mount is `403`;
- `StandaloneNioDavHandler` resolves each name as one segment of its file system, follows a symbolic link only
  while it stays inside the root (`403` otherwise, and out of listings), and refuses a name its file
  system reads as more than one segment;
- a `ClusteredNioDavHandler` refuses `/.dav` and everything below it with `403`, under any name the file
  system resolves to it and through any link, unless its `stateVisible()` returns `true`
  ([clustered.md](clustered.md#dav)).

`Destination` and the resources tagged in `If` must be on the request's `Host` and in the same mount;
otherwise `COPY` and `MOVE` are `502`, and the `If` list does not hold.

## XML

Bodies of `PROPFIND`, `PROPPATCH` and `LOCK` are parsed with the JDK's StAX parser with DTD support
off and external entities never resolved: an entity a document type declaration defines is not
expanded, and a body that uses one is `400`, whether it is external or nested. A body over 1 MiB is
`413`.

## Limits

| Limit | Value |
|---|---|
| Lock timeout | `DavModule.maxLockTimeout`, 1 hour unless set, at least 600 seconds |
| Locks on one resource | 32, then `503` |
| Locks on one storage | 1,024, then `503` |
| Dead properties in memory | 65,536 characters per resource, then `507` |
| Bytes a storage holds | The handler's `quota`, none unless set, then `507` ([quota.md](quota.md)) |
| XML body | 1 MiB, then `413` |
| Request body | The server's `maxBodySize`, 10 MiB by default |

A rate limit (`@Limit` from flash-ext-limiter) covers every method of the mount. Finder sends many
`PROPFIND` requests while browsing, so a low limit stops it.
