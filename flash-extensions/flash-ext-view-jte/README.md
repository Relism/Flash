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

## Quick Start

```java
import dev.relism.extension.FlashApp;
import dev.relism.ext.view.core.ViewModel;
import dev.relism.ext.view.jte.*;

FlashApp.create(8080)
    .install(new JteExtension(cfg -> cfg
        .templateRoot("/templates")
        .contentType(gg.jte.ContentType.Html)))
    .scan("com.example.web")
    .startAndBlock();
```

```java
import dev.relism.ext.view.core.ViewModel;
import dev.relism.ext.view.jte.*;
import dev.relism.routing.GET;

@GET("/")
@Template("pages/home.jte")
public final class HomePage extends JteHandler {
    @Override
    public ViewModel render(dev.relism.models.Request req) {
        return ViewModel.empty()
            .with("page", new HomePageModel("Flash + jte", "elorc"))
            .with("build", "dev");
    }

    public record HomePageModel(String title, String author) {}
}
```

## Docs

- `docs/architecture.md`
- `docs/handlers.md`
- `docs/model-and-globals.md`
- `docs/performance.md`
