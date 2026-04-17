# flash-ext-jackson

Jackson JSON integration for Flash with an opinionated auto-marshal middleware.

## What it provides

| Component | Description |
|---|---|
| `JacksonExtension` | Registers JSON services into `FlashContext` |
| `Json` | JSON read/write helper (`body`, `bodyFrom`, `write`, `writeView`) |
| `ObjectMapper` | Raw mapper escape hatch for advanced usage |
| `JacksonMiddleware` | `autoJson()` middleware for automatic outbound JSON marshalling |

Default mapper behavior (`new JacksonExtension()`):

- auto-discovers Jackson modules on classpath (`findAndAddModules()`)
- includes Java Time support (`jackson-datatype-jsr310`)
- writes date/time values as ISO-8601 strings (not numeric timestamps)

## Recommended default

Install the extension, then apply `autoJson()` once at app or scope level.

```java
JacksonExtension jackson = new JacksonExtension();

FlashApp app = FlashApp.create(8080)
    .install(jackson)
    .use(jackson.autoJson());

app.startAndBlock();
```

Behavior of `autoJson()`:

- pass-through: `null`, `Response`, `byte[]`, `String`, `CharSequence`
- any other return value: serialize to JSON `byte[]`
- sets `Content-Type: application/json` for marshalled responses
- serialization failures throw `IllegalStateException`

This keeps handlers concise while preserving Flash's direct byte write path.

## Installation

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-jackson</artifactId>
    <version>1.1-indev2</version>
</dependency>
```

## Json helper API

Use `Json` when you want explicit, local control in a handler.

```java
@POST("/users")
public final class CreateUser extends RequestHandler {
    private Json json;

    @Override
    protected void onInit() {
        json = require(Json.class);
    }

    @Override
    public Object handle(Request req, Response res) throws Exception {
        CreateUserBody body = json.body(req, CreateUserBody.class);
        UserDto created = service.create(body);
        res.status(201);
        return json.write(res, created);
    }
}
```

Methods:

- `body(req, Type.class)` -> parse from `req.body().bytes()`
- `bodyFrom(req, Type.class)` -> parse from `req.body().stream()`
- `write(res, obj)` -> writes JSON string and sets JSON content type
- `writeView(res, obj, View.class)` -> JSON with Jackson `@JsonView`
- `mapper()` -> raw `ObjectMapper`

## Custom mapper

```java
ObjectMapper mapper = JsonMapper.builder()
    .addModule(new JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .build();

FlashApp.create(8080)
    .install(new JacksonExtension(mapper));
```

## Scope usage

`autoJson()` works the same at scope level:

```java
JacksonExtension jackson = new JacksonExtension();

app.mount("/api", api -> {
    api.use(jackson.autoJson());
    api.get("/health", (req, res) -> Map.of("ok", true));
});
```

If you need to pull it from context, `JacksonMiddleware` is also provided as a service
after the app boots (same lifecycle model as other extension-provided services).

## Notes

- Install order is irrelevant (Flash two-phase extension lifecycle).
- `autoJson()` and OpenAPI are intentionally decoupled.
