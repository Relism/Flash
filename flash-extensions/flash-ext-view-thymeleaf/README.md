# flash-ext-view-thymeleaf

Opinionated Thymeleaf SSR extension for Flash.

This module keeps Thymeleaf semantics front and center:

- `ThymeleafExtension`
- `ThymeleafHandler`
- `@Template` and `@Fragment`
- `ViewModel` from `flash-ext-view-core`
- `global.*` reserved namespace

The extension uses Thymeleaf-native patterns (`template` names resolved by prefix/suffix,
fragment selection via `template :: fragment`) while keeping Flash boot-time fail-fast checks.

## Quick Start

```java
import thymeleaf.view.ext.dev.relism.flash.ThymeleafExtension;

FlashApp.create(8080)
    .

install(new ThymeleafExtension()
        .

addGlobal("appName",req ->"Flash")
        .

addGlobal("requestPath",req ->req.

path()))
        .

scan("com.example.web")
    .

startAndBlock();
```

```java
import thymeleaf.view.ext.dev.relism.flash.Template;
import thymeleaf.view.ext.dev.relism.flash.ThymeleafHandler;
import core.view.ext.dev.relism.flash.ViewModel;
import models.dev.relism.flash.Request;
import routing.dev.relism.flash.GET;

@GET("/")
@Template("pages/home")
public final class HomePage extends ThymeleafHandler {
    @Override
    public ViewModel render(Request req) {
        return ViewModel.of("title", "Home");
    }
}
```

## Fragment Example

```java
import models.dev.relism.flash.Request;

@GET("/users/rows")
@Fragment(template = "fragments/users", value = "rows")
public final class UserRows extends ThymeleafHandler {
    @Override
    public ViewModel render(Request req) {
        return ViewModel.of("users", List.of());
    }
}
```

## Docs

- `docs/architecture.md`
- `docs/handlers.md`
- `docs/model-and-globals.md`
- `docs/fragments.md`
- `docs/performance.md`
