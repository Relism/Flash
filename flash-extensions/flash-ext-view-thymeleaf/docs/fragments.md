# Fragments

Use `@Fragment` for Thymeleaf fragment responses.

```java
@GET("/users/table")
@Fragment(template = "fragments/users", value = "rows")
public final class UsersRows extends ThymeleafHandler {
    @Override
    public ViewModel render(dev.relism.models.Request req) {
        return ViewModel.of("users", List.of());
    }
}
```

If `value` is blank, runtime uses the configured default fragment (default: `content`).
