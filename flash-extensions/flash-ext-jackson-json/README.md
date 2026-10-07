# flash-ext-jackson-json

JSON request bodies and responses through Jackson. Installs the `Json` codec and provides
`JsonHandler<I, O>`.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-jackson-json</artifactId>
</dependency>
```

```java
App app = App.create()
        .install(new JsonModule())
        .scan("com.acme.api");

@POST("/users")
final class CreateUser extends JsonHandler<NewUser, User> {
    private final UserService users;
    CreateUser(UserService users) { this.users = users; }

    @Override protected User handle(Request request, Response response, NewUser body) {
        return users.create(body);
    }
}
```

| Page | Content |
|---|---|
| [docs/handlers.md](docs/handlers.md) | `JsonHandler`, errors, validation |
| [docs/configuration.md](docs/configuration.md) | The mapper, and using `Json` directly |
