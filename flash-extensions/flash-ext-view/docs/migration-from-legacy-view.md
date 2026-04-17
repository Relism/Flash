# Migration from Legacy View API

Legacy API (`@View`, `ViewEngine`, `Renderer`, `Template`) has been removed.

## Replace annotations

- `@View("page")` -> `@Page("page")`
- `@View(value = "x", fragment = true)` -> `@Partial(template = "x")`

`@Page` and `@Partial` must be declared on classes extending `ViewHandler`.

## Replace handler return contract

Before (legacy):

```java
public Object handle(Request req, Response res) {
    return Map.of("name", "Flash");
}
```

Now:

```java
public ViewModel render(Request req) {
    return ViewModel.of("name", "Flash");
}
```

## Replace engine integration

- Implement `ViewEngineAdapter` directly.
- Or use `ViewEngineType.THYMELEAF`.
