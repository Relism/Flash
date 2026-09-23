# flash-ext-openapi

OpenAPI 3.0.3 generation and Swagger UI, built from what the handlers already say about
themselves.

## What it provides

| Route | Description |
|---|---|
| `GET /openapi.json` | OpenAPI spec JSON |
| `GET /openapi.yaml` | OpenAPI spec YAML |
| `GET /openapi/swagger` | Swagger UI |

```java
FlashApp.create(8080)
    .install(new JacksonExtension())
    .install(new OpenApiExtension("/openapi", "My API", "1.0.0"))
    .scan("com.acme.handlers")
    .startAndBlock();
```

## What you get without writing anything

Every class-based route is documented, annotated or not. Read off the code:

- **path parameters**, from `/{id}` in the route
- **the request body**, from the handler's own body type (see below)
- **the response schema**, from what `handle` returns — an object, a `List<T>`, a `Map<String, T>`
- **the media types**: `@Consumes` for what it reads, `@Produces` for what it answers, which are
  two different questions — taking a JSON body does not make the answer JSON
- **error responses**, in the one shape Flash answers failures with: `{"error": "...", "status": 404}`
- **security and rate limiting**, from the extensions that enforce them

Annotations add what the code cannot say: prose, extra statuses, examples. They never repeat it.

## Leaving a route out

```java
@GET("/healthz")
@Undocumented
public final class Health extends RequestHandler { ... }
```

Every route is documented, so a document never lies by omission. `@Undocumented` says a route is
not part of the API — a health check, an internal callback, something on its way out. On a base
class it leaves out every handler written against it.

## Request bodies

A handler that extends `BodyHandler` — `JsonHandler` and `XmlHandler`, and anything else that
reads a format — declares its body type in its signature, and that is the whole documentation:

```java
@POST("/users")
public final class CreateUser extends JsonHandler<NewUser> {
    @Override protected Object handle(Request req, Response res, NewUser body) {
        return users.create(body);
    }
}
```

```yaml
requestBody:
  required: true
  content:
    application/json:
      schema: { $ref: '#/components/schemas/NewUser' }
```

The media type of the body comes from `@Consumes` on the base class, so an XML handler documents
itself as XML without a word from the route.

For a handler that reads the body by hand, or to describe it as something else, declare it:

```java
@PUT("/users")
@RequestBody(value = User.class, array = true, description = "Users to store")
public final class ReplaceUsers extends RequestHandler { ... }
```

## Operations

```java
@GET("/users/{id}")
@ApiOperation(summary = "Get user", description = "Returns one user", tags = {"users"})
@Parameter(name = "expand", in = ParameterIn.QUERY, type = SchemaType.STRING, examples = {"roles", "permissions"})
public final class GetUser extends RequestHandler { ... }
```

`@ApiOperation` is optional: without it the route is still in the document, with no summary.

## Responses

The success response is inferred. Declare one only to say more:

```java
@APIResponse(responseCode = "200", description = "User found",
             content = @Content(schema = UserDto.class, example = "{\"id\":\"usr-1\"}"))
@APIResponse(responseCode = "409", description = "That email is taken")
@APIResponse(responseCode = "204", description = "Deleted")
```

- `content.schema` omitted on a 2xx: the handler's return type.
- Any 4xx or 5xx without an explicit schema: Flash's error object, referenced from `components` —
  in JSON, which is what Flash answers a failure with whatever the route produces.
- `content.array = true` wraps whichever schema was chosen.
- No schema and nothing to infer one from: a response with no body, which is what a 204 is.

The media type of every answer is the handler's `@Produces`, JSON when nothing says otherwise. It
is not on `@Content`: one handler answers in one format, and a status code does not change that.

**A response several operations share is written once.** Identical answers — the 401 of every
guarded route, the 429 of every limited one — become `components.responses` entries referenced by
`$ref`, instead of being repeated on every path.

## DTO schemas

```java
@Schema(name = "User", title = "User DTO", description = "Public user")
public record UserDto(
        @SchemaProperty(title = "ID", example = "USR-100") String id,
        @SchemaProperty(hidden = true) String internalDebug) {}
```

Each type is described once under `components.schemas` and referenced everywhere it appears.
Field-level exclusion: `@Schema(hidden = true)`, `@SchemaProperty(hidden = true)`, `@JsonIgnore`,
`@JsonIgnoreProperties`, `transient`, `static`. `jakarta.validation` constraints (`@NotNull`,
`@NotBlank`, `@NotEmpty`, `@Size`, `@Min`, `@Max`, `@Email`, `@Pattern`) become the schema's own
bounds and required fields, so a rule is written once and documented for free.

## Contributor API

OpenAPI is extension-agnostic. Other extensions contribute through `OpenApiContributor`, held in
`OpenApiContributorRegistry`:

- `components` fragments (merged last-wins)
- operation `security` requirements (additive)
- operation `responses` and response `headers` (additive)

Manual `@APIResponse` description always wins over a contributor's for the same status.

### Security interop

With `flash-ext-security-core` installed, every registered mechanism's scheme lands under
`components.securitySchemes`, and every operation carrying a security annotation lists them as
`security` alternatives with automatic `401` and — for roles or scopes — `403` responses.

### Limiter interop

When `flash-ext-limiter` is installed, handlers with `@Limit` document `X-RateLimit-Limit`,
`X-RateLimit-Remaining`, `X-RateLimit-Reset`, and a `429` with `Retry-After`.

## Notes

- Operations come from the final boot-time routes, so documented paths match runtime paths,
  namespaces, prefixes and rewrites included.
- Lambda routes are not documented: there is no class to read.
- Responses are sorted by status code; the document is rebuilt only when a route is added.
