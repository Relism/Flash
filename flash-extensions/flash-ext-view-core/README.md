# flash-ext-view-core

Minimal runtime primitives for Flash SSR view extensions.

This module is intentionally small and not engine-opinionated. It provides:

- `BaseViewHandler`
- `BaseViewExtension`
- `ViewRuntimeBridge`
- `ViewModel`
- `RenderedView`
- `GlobalValue`

Engine semantics (`@Template`, `@Fragment`, layout/macros/tags, resolver conventions)
belong in engine-specific modules such as `flash-ext-view-thymeleaf`.
