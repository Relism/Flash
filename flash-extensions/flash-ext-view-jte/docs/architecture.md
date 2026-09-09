# Architecture

`flash-ext-view-jte` has two layers:

1. **Boot-time**
   - `JteExtension` installs runtime + annotation processor.
   - `JteTargetResolver` validates handlers and resolves `@Template`.
   - Resolved targets are cached per handler class.

2. **Request-time**
   - Handler builds local `ViewModel`.
   - Runtime merges globals and local model into one flat parameter map (fails fast on key collision).
   - jte renders template into `StringOutput`.

3. **Static assets (optional, enabled by default)**
   - Extension mounts wildcard routes under `staticPrefix` (default `/static/**`).
   - Assets are served from classpath `/static/**`.
   - Uses Flash `Response.body(...)` for small payloads and `Response.stream(...)` for large payloads.
   - Supports ETag/304, gzip precompressed assets, MIME auto-resolution, and byte ranges.

## Handler Contract

- Must extend `JteHandler`.
- Must have route annotation (`@Route`, `@GET`, `@POST`, ...).
- Must declare exactly one view annotation: `@Template`.

Invalid configurations fail fast at startup.

## Defaults

- `templateRoot`: `/templates`
- `contentType`: `gg.jte.ContentType.Html`
- `developmentMode`: `Flash.DEV`
- `usePrecompiled`: derived from `!developmentMode` unless explicitly set
- `binaryStaticContent`: `false`
- `serveStatics`: `true`
- `staticPrefix`: `/static`
- `largeFileThresholdBytes`: `65536`
- static CORS: disabled (`false`)
