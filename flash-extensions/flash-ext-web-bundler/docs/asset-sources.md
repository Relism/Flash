# Asset Sources

`flash-web-bundler` uses explicit source objects for production assets.

Supported sources:

- `FilesystemAssetsSource.of(Path)`
- `ClasspathAssetsSource.of(String rootPrefix)`

Builder shortcuts:

- `.assetsFromFilesystem(Path.of("dist"))`
- `.assetsFromClasspath("web/dist")`

Classpath source requires `asset-manifest.json` generated at build time.
The developer does not maintain this file manually.

Production startup is fail-fast if:

- manifest is missing
- manifest is invalid
- manifest points to missing resources
