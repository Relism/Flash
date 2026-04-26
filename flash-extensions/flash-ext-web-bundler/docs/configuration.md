# Configuration

`WebBundlerConfig` is immutable and built with `WebBundlerConfig.builder()`.

Key fields:

- `runtimeMode`: `PROD`, `ENV`, `AUTODETECT`
- `operationMode`: `ORCHESTRATE_ONLY`, `MANAGED`
- `frontendType`: currently `VITE`
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

- invalid `devPort`
- blank/invalid watch entries
- invalid `basePath`
