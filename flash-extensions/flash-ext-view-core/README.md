# flash-ext-view-core

The shared part of the template engine modules: `ViewHandler`, `ViewModel`, per-request globals and
the `ViewEngine` contract. Install an engine module ([jte](../flash-ext-view-jte/README.md) or
[Thymeleaf](../flash-ext-view-thymeleaf/README.md)), not this one.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-view-core</artifactId>
</dependency>
```

```java
@GET("/")
@Template("home.jte")              // the annotation belongs to the engine module
final class Home extends ViewHandler {
    @Override public ViewModel render(Request req, Response res) {
        return ViewModel.of("name", "Ada");
    }
}
```

| Page | Content |
|---|---|
| [docs/handlers.md](docs/handlers.md) | `ViewHandler`, `ViewModel`, globals, writing an engine |
