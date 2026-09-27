# flash-ext-openapi

OpenAPI 3.0.3 generation and a documentation page, built from what the handlers already say
about themselves.

## What it provides

| Route | Description |
|---|---|
| `GET /openapi.json` | OpenAPI spec JSON |
| `GET /openapi.yaml` | OpenAPI spec YAML |
| `GET /openapi/docs` | The documentation page: Swagger UI, Redoc or Scalar |

```java
App.create()
    .install(new JsonModule(), new OpenApiModule("/openapi", "My API", "1.0.0"))
    .scan("com.acme.handlers");
```

Both documents are encoded once, on the first request, and served as those same bytes afterwards.

## The documentation page

Swagger UI by default; Redoc and Scalar are one call away, and `Ui.none()` serves no page at all.
Each is loaded from jsDelivr at a pinned version, and configured with its own options:

```java
new OpenApiModule("/openapi", "My API", "1.0.0")
    .ui(Ui.scalar().theme(Ui.Scalar.Theme.DEEP_SPACE).layout(Ui.Scalar.Layout.CLASSIC)
                   .darkMode(true).hideModels(true))
```

| | Named options |
|---|---|
| `Ui.swagger()` | `docExpansion`, `modelsExpandDepth`, `deepLinking`, `filter`, `tryItOut`, `persistAuthorization`, `syntaxTheme`, `sortAlphabetically` |
| `Ui.redoc()` | `hideDownloadButton`, `disableSearch`, `requiredPropsFirst`, `sortPropsAlphabetically`, `jsonSampleExpandLevel`, `hideSchemaTitles`, `pathInMiddlePanel`, `hideHostname`, `nativeScrollbars`, `menuToggle` |
| `Ui.scalar()` | `theme`, `layout`, `darkMode`, `hideDarkModeToggle`, `hideModels`, `hideSearch`, `hideTestRequestButton`, `hideClientButton`, `showSidebar`, `defaultOpenAllTags`, `sortOperationsBy` |

The names are the bundles' own, so their documentation is the reference. What is not named here
still gets through — `option("theme", Map.of("colors", …))` passes Redoc a whole theme object.
Two more apply to all three: `customCss(…)`, appended to the page, and `cdn(…)`, which points the
bundle at a mirror, a proxy, or files the application serves itself.

The page is rendered once, at boot.

## What you get without writing anything

Every class-based route is documented, annotated or not. Read off the code:

- **path parameters**, from `/{id}` in the route
- **the request body and the response schema**, from the types the handler names (see below), or
  from what `handle` returns — an object, a `List<T>`, a `Map<String, T>`
- **the media types**, from the codecs the handler names: what it reads and what it answers are two
  different questions, and taking a JSON body does not make the answer JSON
- **error responses**, in the one shape Flash answers failures with: `{"error": "...", "status": 404}`
- **security and rate limiting**, from the modules that enforce them

Annotations add what the code cannot say: prose, extra statuses, examples. They never repeat it.

## Leaving a route out

```java
@GET("/healthz")
@Undocumented
public final class Health implements Handler { ... }
```

Every route is documented, so a document never lies by omission. `@Undocumented` says a route is
not part of the API — a health check, an internal callback, something on its way out. On a base
class it leaves out every handler written against it.

## Request bodies

A handler that names its types — `JsonHandler`, `XmlHandler`, anything over
[`flash-ext-serde`](../flash-ext-serde) — declares both in its signature, and that is the whole
documentation:

```java
@POST("/users")
public final class CreateUser extends JsonHandler<NewUser, User> {
    @Override protected User handle(Request req, Response res, NewUser body) {
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

The media type comes from the codec that reads it, so a handler reading XML documents itself as XML
without a word from the route — and one that reads XML and answers JSON is documented as both.

For a handler that reads the body by hand, or to describe it as something else, declare it:

```java
@PUT("/users")
@RequestBody(value = User.class, array = true, description = "Users to store")
public final class ReplaceUsers implements Handler { ... }
```

## Operations

```java
@GET("/users/{id}")
@ApiOperation(summary = "Get user", description = "Returns one user", tags = {"users"})
@Parameter(name = "expand", in = ParameterIn.QUERY, type = SchemaType.STRING, examples = {"roles", "permissions"})
public final class GetUser implements Handler { ... }
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

The media type of every answer is its codec's, or the handler's `@Produces` when it writes the body
itself, and JSON when nothing says otherwise. It is not on `@Content`: one handler answers in one
format, and a status code does not change that.

`@Consumes` and `@Produces` live here, and are for a handler that reads or writes its body by hand:
one whose codecs are in its signature is described by them and needs neither.

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
`@JsonIgnoreProperties`, `transient`, `static`.

**A validation engine's rules become the schema's own bounds**, so a rule is written once and
documented for free. This module knows no constraint by name: the engine says what each of its
annotations means and contributes it, which is why the document describes what is actually enforced.
See [`flash-ext-validation-core`](../flash-ext-validation-core). With the jakarta engine installed,
a property looks like this — the message of a `@Pattern` lands in the description too, because a
regex is precise and unreadable and both belong in the document:

```java
@SchemaProperty(description = "Unique in the project.")
@Pattern(regexp = "[a-z.]+", message = "uses lowercase letters and dots")
String key
```

```yaml
key:
  type: string
  description: Unique in the project. Uses lowercase letters and dots.
  pattern: '[a-z.]+'
```

Only a rule with no readable keyword does this: `required`, `maxLength` and `format: email` speak
for themselves, and repeating them as prose would be noise.

## Contributor API

OpenAPI knows no other module. Others contribute through `OpenApiContributor`, added as a service
(`app.add(OpenApiContributor.class, MyDocs.class)`) and read with every contributor when the app
starts. A module installs its contributor with `Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor", ...)`,
so it does not need flash-ext-openapi on the classpath:

- `components` fragments (merged last-wins)
- operation `security` requirements (additive)
- operation `responses` and response `headers` (additive)
- a property's wire name, whether it is left out, and the rules a validation engine knows

Manual `@APIResponse` description always wins over a contributor's for the same status.

### Security interop

With `flash-ext-security-core` installed, every registered mechanism's scheme lands under
`components.securitySchemes`, and every operation carrying a security annotation lists them as
`security` alternatives with automatic `401` and — for roles or scopes — `403` responses.

### Limiter interop

When `flash-ext-limiter` is installed, handlers with `@Limit` document `X-RateLimit-Limit`,
`X-RateLimit-Remaining`, `X-RateLimit-Reset`, and a `429` with `Retry-After`.

## Notes

- Operations come from the routes the app builds at start, so documented paths match runtime
  paths, mount prefixes included.
- Lambda routes are not documented: there is no class to read.
- Responses are sorted by status code.
