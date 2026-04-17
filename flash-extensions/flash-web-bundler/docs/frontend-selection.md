# Frontend Selection

Frontend integration is explicit through `frontendType`.

- No heuristic detection in v1.
- Deterministic mapping: `FrontendType -> FrontendStrategy`.
- Current built-in strategy: `VITE`.

Extension points:

- register custom `FrontendStrategy` implementations in the resolver.
- override default commands per config.
