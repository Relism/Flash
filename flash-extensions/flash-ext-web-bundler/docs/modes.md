# Modes

## Runtime Mode

- `PROD`: production static serving only.
- `AUTODETECT`: uses `Flash.DEV`.
- `ENV`: uses `FLASH_WEB_BUNDLER_MODE` (`dev` => dev mode, else prod).

## Operation Mode

- `ORCHESTRATE_ONLY`: only orchestrates dev tooling.
- `MANAGED`: enables production serving + SPA fallback routes.
