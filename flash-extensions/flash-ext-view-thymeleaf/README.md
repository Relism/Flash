# flash-ext-view-thymeleaf

Thymeleaf templates for Flash, read from the classpath.

```java
App.create()
    .install(new ThymeleafModule().global("appName", req -> "Flash").global("path", Request::path))
    .scan("com.example.web");
```

```java
@GET("/")
@Template("pages/home")
public final class HomePage extends ViewHandler {
    @Override public ViewModel render(Request req, Response res) {
        return ViewModel.of("title", "Home");
    }
}

@GET("/users/rows")
@Fragment(template = "fragments/users", value = "rows")
public final class UserRows extends ViewHandler {
    private final Users users;

    public UserRows(Users users) { this.users = users; }

    @Override public ViewModel render(Request req, Response res) {
        return ViewModel.of("users", users.all());
    }
}
```

A handler names either a `@Template` or a `@Fragment`, never both. A `@Fragment` without a
value renders the module's default fragment, `content`. Both annotations are inherited.

## Globals

Globals are under `global`: `<span th:text="${global.appName}">`. A handler's model cannot use
that key. Every global is computed on every render, so keep them cheap.

## Links

`@{/users/{id}(id=${id},page=${page})}` fills path variables and writes the other parameters as
the query, both URL-encoded: `/users/42?page=2`.

## Settings

| Method | Default |
|---|---|
| `prefix(String)` | `/templates/` |
| `suffix(String)` | `.html` |
| `mode(TemplateMode)` | `HTML` |
| `cache(boolean)` | outside dev (`ServerConfig.dev`) |
| `defaultFragment(String)` | `content` |
