# Static files

`StaticFiles` serves a classpath directory from memory. It reads every file once at boot and
prepares the bytes and headers then, so serving allocates nothing.

```java
StaticFiles files = new StaticFiles(App.class.getClassLoader(), "static", "/assets", StaticFiles.HASHED);
app.get("/assets/**", files);
```

| Argument | Meaning |
|---|---|
| `loader` | Class loader that holds the directory |
| `root` | Classpath directory, for example `static`. Missing: `IllegalStateException` |
| `basePath` | URL prefix. `static/app.css` is served at `/assets/app.css` |
| `immutable` | Predicate on the path relative to `root`. True means cache forever |

The handler answers `404` through `HttpException` for any other path. `serve(path, req, res)` does the
same lookup for any path and returns `false`, writing nothing, when there is no file. Use it to fall
back to something else, such as an `index.html` for client-side routes. `paths()` lists the served
paths.

## Caching

| File | `Cache-Control` | Validators |
|---|---|---|
| `immutable` returns true | `public, max-age=31536000, immutable` | none |
| anything else | `no-cache` | `ETag` (SHA-1 of the content, quoted). A matching `If-None-Match` answers `304` |

`StaticFiles.HASHED` marks names that contain a hex hash of at least 8 digits between dots, for
example `app.3f2a91bc.js`.

## Compression

A `.gz` file next to `app.js` (`app.js.gz`) is served to clients whose `Accept-Encoding` lists `gzip`
with a non-zero `q`. The response carries `Content-Encoding: gzip` and `Vary: Accept-Encoding`. Other
clients get the uncompressed file. Flash does not compress anything itself.

## Other behavior

- `Content-Type` comes from the file extension (`ContentType.ofExtension`).
- A file named `index.html` also answers at its directory, with and without a trailing slash.
- Everything is held in memory. Do not point it at large files.
