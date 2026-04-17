# Performance

`flash-ext-view` is optimized for low overhead on request path.

## Current runtime choices

- Handler view metadata resolved once and cached.
- Global/local model merge done in a single pass.
- No legacy rendering branches in runtime pipeline.

## Best practices

- Cache services in `onViewInit()`.
- Keep `addGlobal(...)` resolvers cheap and side-effect free.
- Build only the model fields needed by template.
- Avoid blocking I/O in `render(...)`; delegate to precomputed service data when possible.
