# Architecture

`flash-ext-view` runs in two layers:

1. **Boot-time**
   - `ViewExtension` registers `ViewRuntime` and an annotation processor.
   - `ViewTargetResolver` validates handlers and maps annotations to `ViewTarget`.
   - Resolved targets are cached per handler class.

2. **Request-time**
   - Handler builds local `ViewModel`.
   - `ViewRuntime` injects extension globals under reserved `global` namespace, then merges local model.
   - `ViewEngineAdapter` renders `RenderOutput`.

## Valid Handler Contract

- Must extend `ViewHandler`.
- Must have route annotation (`@Route`, `@GET`, `@POST`, ...).
- Must declare exactly one view annotation:
  - `@Page`
  - `@Partial`

Invalid configurations fail fast at startup.
