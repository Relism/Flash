# Handlers

Use `ThymeleafHandler` for class-based Thymeleaf routes.

## Lifecycle

- `onViewInit()` runs once at boot.
- `render(...)` runs per request.

Use `onViewInit()` to cache dependencies via `require(...)`.

## Example

```java
@GET("/dashboard")
@Template("pages/dashboard")
public final class DashboardPage extends ThymeleafHandler {

    private DashboardService service;

    @Override
    protected void onViewInit() {
        service = require(DashboardService.class);
    }

    @Override
    public ViewModel render(dev.relism.models.Request req) {
        return ViewModel.empty()
            .with("title", "Dashboard")
            .with("stats", service.stats());
    }
}
```

Use `render(Request, Response)` when you need response access while building model variables.
