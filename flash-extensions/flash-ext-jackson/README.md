# flash-ext-jackson

Jackson JSON integration for the Flash HTTP server.

## What it provides

| Component | Description |
|---|---|
| `JacksonExtension` | Installs Jackson into the extension layer |
| `JacksonHandler` | Base class for handlers that need JSON I/O |
| Global exception handler | Maps `HttpException` → JSON error; all other exceptions → 500 |

## Installation

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-jackson</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Install before any extension that needs JSON (e.g. `flash-ext-openapi`, `flash-ext-oidc`):

```java
FlashApp.create(8080)
    .install(new JacksonExtension())
    // ... other extensions
```

### Custom mapper

```java
ObjectMapper mapper = JsonMapper.builder()
    .addModule(new JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .build();

FlashApp.create(8080)
    .install(new JacksonExtension(mapper));
```

## JacksonHandler

Extend `JacksonHandler` to get `bodyAs` and `json` helpers without constructor boilerplate.
The `ObjectMapper` is injected once at startup and shared across all subclasses.

```java
@Route(method = HttpMethod.POST, path = "/api/blogs")
@ApiOperation(summary = "Create blog")
@ApiResponse(status = 201, description = "Created", schema = Blog.class)
@ApiResponse(status = 400, description = "Invalid body")
public class CreateBlog extends JacksonHandler {

    @Override
    public Object handle(Request req, Response res) throws Exception {
        CreateBlogRequest body = bodyAs(req, CreateBlogRequest.class);
        Blog created = service.create(body);
        res.setStatusCode(201);
        return json(res, created);         // sets Content-Type: application/json
    }
}
```

### Methods

| Method | Description |
|---|---|
| `bodyAs(req, Type.class)` | Deserializes the request body; throws `HttpException` 400 on parse errors |
| `json(res, obj)` | Serializes `obj`, sets `Content-Type: application/json`, returns the JSON string |
| `jsonView(res, obj, View.class)` | Like `json` but applies a Jackson `@JsonView` filter |

## Exception handling

`JacksonExtension` installs a global exception handler that applies to every route:

```
HttpException(status, message)  →  HTTP <status>  {"error": "<message>"}
Any other Throwable             →  HTTP 500        {"error": "Internal Server Error"}
```

To throw a handled HTTP error from any handler:

```java
throw HttpException.notFound("Blog not found");
throw HttpException.badRequest("Missing field: title");
throw HttpException.unauthorized();
throw HttpException.forbidden();
```

## Lambda routes

For lambda-style routes, use the `ObjectMapper` directly from the context:

```java
ObjectMapper mapper = app.ctx().require(ObjectMapper.class);

app.get("/api/status", (req, res) -> {
    res.setContentType(ContentType.JSON);
    return mapper.writeValueAsString(Map.of("status", "ok"));
});
```
