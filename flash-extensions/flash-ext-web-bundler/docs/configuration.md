# Configuration

`WebBundlerConfig` is immutable and built with `WebBundlerConfig.builder()`.

Key fields:

- `runtimeMode`: `PROD`, `ENV`, `AUTODETECT`
- `operationMode`: `ORCHESTRATE_ONLY`, `MANAGED`
- `frontendType`: `VITE`, `STATIC` — see `frontend-selection.md`
- `packageManager`: `NPM`, `PNPM`, `YARN`, `BUN`
- `installPolicy`: `AUTO_IF_LOCK_HASH_CHANGED`, `NEVER`
- `loggingMode`: `MERGED`, `SEPARATE`, `QUIET`, `VERBOSE`
- `commandSafetyMode`: `WARN`, `BLOCK`, `ALLOW`
- `webRoot`, `assetsSource`, `basePath`, `devHost`, `devPort`, `watchList`

Asset source is explicit and object-based:

- `assetsFromFilesystem(Path.of("dist"))`
- `assetsFromClasspath("web/dist")`

Or by direct source object:

- `assetsSource(FilesystemAssetsSource.of(Path.of("dist")))`
- `assetsSource(ClasspathAssetsSource.of("web/dist"))`

Validation is fail-fast:

- invalid `devPort` (only when `frontendType` requires orchestration — skipped for `STATIC`)
- blank/invalid watch entries (same — skipped for `STATIC`)
- invalid `basePath`

`frontendType(...)` has side effects on other defaults, same pattern as `packageManager(...)`
resetting `watchList`: it also resets `operationMode` (`MANAGED` for `STATIC`, `ORCHESTRATE_ONLY`
otherwise) and `assetsSource` (`webRoot` itself for `STATIC`, `webRoot/dist` otherwise). Call
`.frontendType(...)` before any explicit `.operationMode(...)`/`.assetsSource(...)`/`.assetsFrom*(...)`
override, or the later call wins.
