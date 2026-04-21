# Architecture

`flash-ext-view-thymeleaf` has two layers:

1. **Boot-time**
   - `ThymeleafExtension` installs runtime + annotation processor.
   - `ThymeleafTargetResolver` validates handlers and resolves `@Template` / `@Fragment`.
   - Resolved targets are cached per handler class.

2. **Request-time**
   - Handler builds local `ViewModel`.
   - Runtime injects globals under reserved `global` namespace and merges local model.
   - Thymeleaf renders template or fragment.

## Handler Contract

- Must extend `ThymeleafHandler`.
- Must have route annotation (`@Route`, `@GET`, `@POST`, ...).
- Must declare exactly one view annotation:
  - `@Template`
  - `@Fragment`

Invalid configurations fail fast at startup.
