# flash-ext-openapi

OpenAPI 3.0 spec generation and Swagger UI for the Flash HTTP server.

## What it provides

| Route | Description |
|---|---|
| `GET /openapi.json` | OpenAPI 3.0.3 spec as JSON |
| `GET /openapi.yaml` | OpenAPI 3.0.3 spec as YAML |
| `GET /openapi/swagger` | Swagger UI (loaded from unpkg CDN) |

The base path is configurable. Operations are collected automatically at handler-registration time
from class-based handlers annotated with `@ApiOperation`.

## Dependencies

Requires `flash-ext-jackson` installed **before** this extension (shares its `ObjectMapper` from context).
If `flash-ext-oidc` is installed **after** this extension, OIDC security schemes are injected automatically.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-openapi</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Installation

```java
FlashApp.create(8080)
    .install(new JacksonExtension())
    .install(new OpenApiExtension("/openapi", "My API", "2.0.0", "Optional description"))
    .register(new MyHandler());
```

### Constructors

```java
new OpenApiExtension()                                     // base path: /openapi, title: API, version: 1.0.0
new OpenApiExtension("/docs")                              // custom base path
new OpenApiExtension("/docs", "My API", "2.0.0")          // title + version
new OpenApiExtension("/docs", "My API", "2.0.0", "desc")  // full
```

## Annotating handlers

All annotations target the **handler class** (`@Target(ElementType.TYPE)`).

### @ApiOperation

```java
@Route(method = HttpMethod.GET, path = "/api/blogs")
@ApiOperation(
    summary     = "List all blogs",
    description = "Returns a paginated list of published blog posts.",
    tags        = {"blogs"},
    operationId = "listBlogs",
    deprecated  = false
)
public class ListBlogs extends JacksonHandler { ... }
```

| Field | Default | Description |
|---|---|---|
| `summary` | `""` | Short one-liner shown in the operation title |
| `description` | `""` | Longer Markdown description |
| `tags` | `{}` | Groups operations in the Swagger UI sidebar |
| `operationId` | `""` | Unique machine-readable ID |
| `deprecated` | `false` | Marks the operation with a strikethrough |

### @ApiResponse

Repeatable — annotate as many status codes as the handler can return.

```java
@ApiResponse(status = 200, description = "Blog created", schema = Blog.class)
@ApiResponse(status = 400, description = "Invalid input")
@ApiResponse(status = 409, description = "Slug already exists")
public class CreateBlog extends JacksonHandler { ... }
```

`schema` references `#/components/schemas/<ClassName>` — you are responsible for populating
`components.schemas` if you need full model documentation (not yet auto-generated).

`@ApiResponse` is repeatable. The container `@ApiResponses({ @ApiResponse(...), ... })` is also available.

### @ApiParam

Repeatable — declare query, path, header, or cookie parameters explicitly.

```java
@ApiParam(name = "limit",  in = "query",   type = "integer", description = "Max results (default 20)")
@ApiParam(name = "offset", in = "query",   type = "integer", description = "Pagination offset")
@ApiParam(name = "slug",   in = "path",    type = "string",  required = true)
@ApiParam(name = "X-Trace-Id", in = "header", type = "string")
public class GetBlog extends JacksonHandler { ... }
```

> Path parameters declared in `@Route(path = "/blogs/{id}")` are extracted and added automatically
> as required path parameters — you only need `@ApiParam` for query / header / cookie params.

`@ApiParam` is repeatable. If you prefer grouping them, `@ApiParams({ @ApiParam(...), @ApiParam(...) })` is
the container annotation.

| Field | Default | Description |
|---|---|---|
| `name` | — | Parameter name |
| `in` | `"query"` | Location: `"query"`, `"path"`, `"header"`, `"cookie"` |
| `type` | `"string"` | OpenAPI primitive: `"string"`, `"integer"`, `"number"`, `"boolean"` |
| `description` | `""` | Human-readable description |
| `required` | `false` | Whether the parameter is mandatory |
| `example` | `""` | Inline example value shown in Swagger UI |

## Security integration

`flash-ext-openapi` defines the `OpenApiSecurityContributor` / `OpenApiSecurityRegistry` contracts.
Security extensions (e.g. `flash-ext-oidc`) register a contributor at install time; the spec
builder picks it up automatically — no coupling between extensions.

### How it works

1. `OpenApiExtension` creates an `OpenApiSecurityRegistry` and exposes it in the `FlashContext`.
2. `flash-ext-oidc` calls `ctx.find(OpenApiSecurityRegistry.class)` and registers its contributor.
3. At spec build time, `OpenApiBuilder` iterates contributors and injects `security` entries on each
   operation whose handler class carries `@Authenticated` or `@RolesAllowed`.

### Implementing a custom contributor

```java
public class MyAuthContributor implements OpenApiSecurityContributor {

    @Override
    public String schemeName() { return "myScheme"; }

    @Override
    public Map<String, Object> schemeDefinition() {
        return Map.of("type", "apiKey", "in", "header", "name", "X-API-Key");
    }

    @Override
    public List<String> requiredFor(Class<?> handlerClass) {
        if (handlerClass.isAnnotationPresent(MyAuth.class)) return List.of();
        return null; // not secured by this contributor
    }
}

// Register during extension install:
ctx.find(OpenApiSecurityRegistry.class)
   .ifPresent(r -> r.add(new MyAuthContributor()));
```

Return values from `requiredFor`:

| Return | Meaning |
|---|---|
| `null` | Handler is not secured by this contributor — skip |
| `List.of()` | Requires authentication, no specific scopes |
| `List.of("admin", "user")` | Requires one of these scopes (OpenAPI OR semantics) |
