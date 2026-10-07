# flash-ext-view-thymeleaf

[Thymeleaf](https://www.thymeleaf.org) templates and fragments for Flash, read from the classpath.
Built on [`flash-ext-view-core`](../flash-ext-view-core/README.md).

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-view-thymeleaf</artifactId>
</dependency>
```

```java
App app = App.create().install(new ThymeleafModule().global("user", req -> currentUser(req))).scan("com.acme.web");

@GET("/users/{id}")
@Template("user")                          // /templates/user.html
final class UserPage extends ViewHandler {
    @Override public ViewModel render(Request req, Response res) {
        return ViewModel.of("id", req.paramLong("id"));
    }
}
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Templates, fragments, globals, links, settings |
