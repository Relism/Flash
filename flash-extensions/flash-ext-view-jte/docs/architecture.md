# Architecture

`flash-ext-view-jte` has two layers:

1. **Boot-time**
   - `JteExtension` installs runtime + annotation processor.
   - `JteTargetResolver` validates handlers and resolves `@Template`.
   - Resolved targets are cached per handler class.

2. **Request-time**
   - Handler builds local `ViewModel`.
   - Runtime injects globals under reserved `global` namespace and merges local model.
   - jte renders template into `StringOutput`.

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
