# Frontend Selection

Frontend integration is explicit through `frontendType`.

- No heuristic detection in v1.
- Deterministic mapping: `FrontendType -> FrontendStrategy`.
- Built-in strategies: `VITE`, `STATIC`.

## VITE

Orchestrates a dev server process in DEV, serves a prebuilt directory in PROD. See `dev-lifecycle.md`.

## STATIC

For files served as-is — no dev server, no package manager, no build step, no watch loop.
`STATIC` never orchestrates, in DEV or PROD: it always loads `assetsSource` directly and serves it,
the same code path `VITE` only uses in PROD. Editing a file during a running dev session requires a
restart to be picked up (assets are preloaded once, same as `VITE`'s prod serving — no hot reload).

Setting `.frontendType(FrontendType.STATIC)` also switches two other defaults (see `configuration.md`):
`operationMode` becomes `MANAGED` and `assetsSource` defaults to the `webRoot` itself instead of a
`dist` subdirectory — a minimal STATIC config is just:

```java
WebBundlerConfig.builder()
    .frontendType(FrontendType.STATIC)
    .webRoot(Path.of("public"))
    .build()
```

Extension points:

- register custom `FrontendStrategy` implementations in the resolver.
- override default commands per config.
