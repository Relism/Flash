# flash-ext-view-jte

Opinionated jte SSR extension for Flash.

This module keeps jte semantics front and center:

- `JteExtension`
- `JteHandler`
- `@Template`
- `ViewModel` from `flash-ext-view-core`
- `global.*` reserved namespace

The extension mirrors `gg.jte.ContentType` into Flash HTTP content type:

- `gg.jte.ContentType.Html` -> `text/html`
- `gg.jte.ContentType.Plain` -> `text/plain`

## Static serving (opinionated)

Static serving is enabled by default and serves classpath assets from `/static/**`
(`src/main/resources/static/**`) on an HTTP wildcard route.

Defaults:

- `serveStatics = true`
- `staticPrefix = "/static"`
- ETag via CRC32 + length
- `304 Not Modified` on `If-None-Match`
- pre-compressed `.gz` support when client sends `Accept-Encoding: gzip`
- MIME auto-resolution for common web/media/font/wasm types
- cache policy:
  - versioned assets (`file.<hash>.ext` or query `?v=...` / `?hash=...`) -> `public, max-age=31536000, immutable`
  - non-versioned -> `no-cache`
- byte-range support for large files (threshold configurable, default 64 KiB)

If classpath `/static` is missing, extension logs a warning and disables static routes.

### Configuration examples

```java
// option 1: customizer constructor
app.install(new JteExtension(cfg -> cfg
    .templateRoot("/templates")
    .serveStatics(true)
    .staticPrefix("/assets")));

// option 2: fluent style
app.install(new JteExtension()
    .templateRoot("/templates")
    .withStaticServing()
    .staticPrefix("/assets"));
```

Disable static serving:

```java
app.install(new JteExtension().serveStatics(false));
```

Enable static CORS (default disabled):

```java
app.install(new JteExtension().staticCors(cfg -> cfg
    .enableStaticCors(true)
    .staticCorsAllowOrigin("*")
    .staticCorsAllowMethods("GET,HEAD,OPTIONS")
    .staticCorsAllowHeaders("*")
    .staticCorsMaxAge(3600)));
```

## Quick Start

```java
import jte.view.ext.dev.relism.flash.JteExtension;

FlashApp.create(8080)
    .

install(new JteExtension(cfg ->cfg
        .

templateRoot("/templates")
        .

contentType(gg.jte.ContentType.Html)))
        .

scan("com.example.web")
    .

startAndBlock();
```

```java
import core.view.ext.dev.relism.flash.ViewModel;
import jte.view.ext.dev.relism.flash.JteHandler;
import jte.view.ext.dev.relism.flash.Template;
import models.dev.relism.flash.Request;
import routing.dev.relism.flash.GET;

@GET("/")
@Template("pages/home.jte")
public final class HomePage extends JteHandler {
    @Override
    public ViewModel render(Request req) {
        return ViewModel.empty()
                .with("page", new HomePageModel("Flash + jte", "elorc"))
                .with("build", "dev");
    }

    public record HomePageModel(String title, String author) {
    }
}
```

## Docs

- `docs/architecture.md`
- `docs/handlers.md`
- `docs/model-and-globals.md`
- `docs/performance.md`
