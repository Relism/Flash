# Asset Sources

`flash-web-bundler` uses explicit source objects for production assets.

Supported sources:

- `FilesystemAssetsSource.of(Path)`
- `ClasspathAssetsSource.of(String rootPrefix)`

Builder shortcuts:

- `.assetsFromFilesystem(Path.of("dist"))`
- `.assetsFromClasspath("web/dist")`

Classpath source requires `asset-manifest.json` generated at build time.
The developer does not maintain this file manually — generate it with `WebBundlerBuild`
(`dev.relism.flash.ext.webbundler.WebBundlerBuild`), which scans a prebuilt directory (a Vite
`dist/` or a `STATIC` asset folder) and writes the manifest into it, ready to be picked up as a
classpath resource once that directory lands under `target/classes`. See `build-time.md`.

`WebBundlerBuild` reuses the exact same etag/mimeType/immutable computation `FilesystemAssetsSource`
uses at runtime, so a file served from disk in dev and the same file served from the classpath in
prod get identical cache semantics.

Production startup is fail-fast if:

- manifest is missing
- manifest is invalid
- manifest points to missing resources
