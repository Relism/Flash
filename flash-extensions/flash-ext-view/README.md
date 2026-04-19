# flash-ext-view

Lightweight SSR view extension for Flash.

This module provides a focused MVC surface:

- `ViewExtension`
- `ViewHandler`
- `@Page` and `@Partial`
- `ViewModel` and opinionated `global.*` values
- `ViewEngineAdapter`

No legacy annotation/renderer API is exposed.

`@Page`/`@Partial` are valid only on `ViewHandler` subclasses.

## Quick Start

```java
import dev.relism.extension.FlashApp;
import dev.relism.ext.view.*;

FlashApp.create(8080)
    .install(new ViewExtension(ViewEngineType.THYMELEAF)
        .addGlobal("appName", req -> "Flash")
        .addGlobal("requestPath", req -> req.path()))
    .scan("com.example.web")
    .startAndBlock();
```

`global` is a reserved namespace. Handlers cannot write a top-level `global` key.

```java
import dev.relism.ext.view.*;
import dev.relism.routing.GET;

@GET("/")
@Page("pages/home")
public final class HomePage extends ViewHandler {
    @Override
    public ViewModel render(dev.relism.models.Request req) {
        return ViewModel.of("title", "Home");
    }
}
```

## Docs

- `docs/architecture.md`
- `docs/handlers.md`
- `docs/model-and-globals.md`
- `docs/partials.md`
- `docs/adapters.md`
- `docs/performance.md`
