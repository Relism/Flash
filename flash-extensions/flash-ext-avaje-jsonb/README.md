# flash-ext-avaje-jsonb

JSON request bodies and responses through [Avaje Jsonb](https://avaje.io/jsonb/). Adapters are
generated at build time, so nothing is reflected on at runtime. It provides the same names as
`flash-ext-jackson-json` (`Json`, `JsonHandler`, `JsonModule`) in the package
`dev.relism.flash.ext.avaje.jsonb`. Use one JSON module, not both.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-avaje-jsonb</artifactId>
</dependency>
```

```java
App app = App.create().install(new JsonModule()).scan("com.acme.api");

@Json
public record NewUser(String name) {}

@POST("/users")
final class CreateUser extends JsonHandler<NewUser, User> {
    @Override protected User handle(Request request, Response response, NewUser body) { ... }
}
```

The build must run the Jsonb generator. See [docs/build.md](docs/build.md).

| Page | Content |
|---|---|
| [docs/build.md](docs/build.md) | Annotation processor, `@Json`, shaded jars |
| [docs/usage.md](docs/usage.md) | Handlers, configuration, differences from Jackson |
