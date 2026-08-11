# Modes

## Runtime Mode

- `PROD`: production static serving only.
- `AUTODETECT`: uses `Flash.DEV`.
- `ENV`: uses `FLASH_WEB_BUNDLER_MODE` (`dev` => dev mode, else prod).

## Operation Mode

- `ORCHESTRATE_ONLY`: only orchestrates dev tooling.
- `MANAGED`: enables production serving + SPA fallback routes.

`FrontendType.STATIC` defaults `operationMode` to `MANAGED` (see `frontend-selection.md`) — `STATIC`
has no dev tooling to orchestrate, so `ORCHESTRATE_ONLY` would make the extension a no-op for it.

## Orchestration

Whether DEV mode spawns a dev-server process at all is a separate axis from Runtime Mode: it also
depends on `frontendType`. `VITE` orchestrates in DEV; `STATIC` never does, in DEV or PROD — it
always loads and serves `assetsSource` directly, the same path `VITE` only takes in PROD.
