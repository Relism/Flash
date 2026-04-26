# flash-web-bundler

`flash-web-bundler` integrates frontend tooling lifecycle into Flash with explicit, policy-driven behavior.

## Quick Start

```java
FlashApp.create(8080)
    .install(new WebBundlerExtension(
        WebBundlerConfig.builder()
            .webRoot(Path.of("web"))
            .basePath("/")
            .build()
    ))
    .start();
```

## Operating Model

- Dev: orchestrates frontend process lifecycle and health checks.
- Prod: serves prebuilt assets from `assetsSource` with ETag/cache/compression support.
- Route precedence: backend first, SPA fallback second (`GET`/`HEAD`).

See also: `asset-sources.md`.
