# Handlers

Use `JteHandler` for class-based jte routes.

## Lifecycle

- `onViewInit()` runs once at boot.
- `render(...)` runs per request.

Use `onViewInit()` to cache dependencies via `require(...)`.

## Example

```java
import models.dev.relism.flash.Request;

@GET("/dashboard")
@Template("pages/dashboard.jte")
public final class DashboardPage extends JteHandler {

    private DashboardService service;

    @Override
    protected void onViewInit() {
        service = require(DashboardService.class);
    }

    @Override
    public ViewModel render(Request req) {
        return ViewModel.empty()
                .with("page", service.page(req));
    }
}
```

Use `render(Request, Response)` when you need response access while building model variables.
