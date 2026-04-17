# Production Serving

In production mode, `flash-web-bundler` serves only prebuilt assets.

Behavior:

- preloads files from `assetsSource` (filesystem or classpath)
- resolves MIME by extension
- supports precompressed siblings (`.br`, `.gz`)
- sets `Cache-Control` and `ETag`
- responds `304 Not Modified` when `If-None-Match` matches

Classpath mode details:

- reads entries from `asset-manifest.json` in classpath root
- validates referenced resources at startup
- fails fast in production if manifest or resources are inconsistent
