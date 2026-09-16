# flash-ext-openapi

OpenAPI 3.0.3 generation + Swagger UI for Flash.

## What it provides

| Route | Description |
|---|---|
| `GET /openapi.json` | OpenAPI spec JSON |
| `GET /openapi.yaml` | OpenAPI spec YAML |
| `GET /openapi/swagger` | Swagger UI |

## Install

```java
FlashApp.create(8080)
    .install(new JacksonExtension())
    .install(new OpenApiExtension("/openapi", "My API", "1.0.0"))
    .scan("com.acme.handlers")
    .startAndBlock();
```

## Operation annotation

```java
@GET("/users/{id}")
@ApiOperation(summary = "Get user", description = "Returns one user", tags = {"users"})
@Parameter(name = "expand", in = ParameterIn.QUERY, type = SchemaType.STRING, examples = {"roles", "permissions"})
@APIResponse(
    responseCode = "200",
    description = "User found",
    content = @Content(contentType = ContentType.JSON, schema = UserDto.class)
)
public final class GetUser extends RequestHandler { ... }
```

## Response patterns

### Single object

```java
@APIResponse(
    responseCode = "200",
    description = "User found",
    content = @Content(contentType = ContentType.JSON, schema = UserDto.class)
)
```

### Array

```java
@APIResponse(
    responseCode = "200",
    description = "Users listed",
    content = @Content(contentType = ContentType.JSON, schema = UserDto.class, array = true)
)
```

### No content

```java
@APIResponse(
    responseCode = "204",
    description = "Deleted",
    content = @Content(contentType = ContentType.NONE)
)
```

### Inferred from handler return type

```java
@APIResponse(
    responseCode = "200",
    content = @Content
)
```

If `content.schema` is omitted, schema is inferred from the handler `handle(...)` return type.
Explicit `content.schema` always wins over inference.

Inference defaults:

- `UserDto` -> object schema for `UserDto`
- `List<UserDto>` / `Set<UserDto>` / `UserDto[]` -> `array` with `items: UserDto`
- `Map<String, UserDto>` -> `object` with `additionalProperties: UserDto`

## DTO schema metadata

```java
@Schema(name = "User", title = "User DTO", description = "Public user", deprecated = false)
public class UserDto {

    @SchemaProperty(title = "ID", required = true, example = "USR-100", enumeration = {"USR-100", "USR-101"})
    public String id;

    @SchemaProperty(hidden = true)
    public String internalDebug;
}
```

Supported field-level exclusion:

- `@Schema(hidden = true)` / `@SchemaProperty(hidden = true)`
- `@JsonIgnore`
- `@JsonIgnoreProperties(...)`
- `transient` / `static`

## Contributor API

OpenAPI is extension-agnostic. Other extensions contribute with `OpenApiContributor` via
`OpenApiContributorRegistry`.

Supported contribution surfaces:

- `components` fragments (merged with last-wins)
- operation `security` requirements (additive)
- operation `responses` and response `headers` (additive)

Merge policy:

- contributor collisions use **last-wins**
- manual `@APIResponse` description always wins over contributors for the same status

## Security interop

With `flash-ext-security-core` installed, every registered mechanism's scheme lands under
`components.securitySchemes`, and every operation carrying a security annotation lists them as
`security` alternatives with automatic `401` and — for roles or scopes — `403` responses.

Manual `@APIResponse` for the same status code always wins.

## Limiter interop

When `flash-ext-limiter` is installed, handlers with `@Limit` automatically get response
headers documented in OpenAPI:

- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`
- `Retry-After` on `429`

If `429` is missing, it is auto-added as `Too Many Requests`.

## Notes

- Operations are collected from final boot-time routes for class-based handlers with `@ApiOperation`.
- Documented paths always match runtime paths (including scope namespaces/prefixes/rewrites).
- Route path params are auto-discovered from `/{id}`.
- Parameter annotations are mainly for query/header/cookie enrichment.
- Output responses are sorted by numeric status code.
