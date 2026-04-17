# Partials

Use `@Partial` for fragment responses.

```java
@GET("/users/table")
@Partial(template = "fragments/users", slot = "rows")
public final class UsersRows extends ViewHandler {
    @Override
    public ViewModel render(dev.relism.models.Request req) {
        return ViewModel.of("users", List.of());
    }
}
```

If slot is empty, the adapter default slot is used.

If a slot is specified but the adapter does not support slot selection,
startup fails with a clear error.
