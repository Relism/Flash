# Operations

Every route built from a handler class or instance becomes one operation at its full path (mount
prefix included) and method. Routes of a `DuplexHandler` (`@Ws`, `@Sse`, `@Grpc`) are not documented.
A route declared with a lambda has no handler class to read, so it is listed with its path and
method and a bare `200 OK` response. This includes the module's own `.json`, `.yaml` and `/docs`
routes.

## What is inferred

| Part | Source |
|---|---|
| Path parameters | `{name}` segments of the route, as required string parameters |
| Request body | The first type argument of a `CodecRequestHandler`, with the media type of its input codec |
| Success response | The type returned by `handle`, or the last type argument of a `CodecRequestHandler`. Media type from its output codec, or JSON |
| `4xx` and `5xx` responses | The shape Flash answers failures with: `{"error": string, "status": integer}`, as `application/json` |
| Security, rate limits | Contributed by the modules that enforce them. See [contributors.md](contributors.md) |

If nothing can be inferred for the success response, the document says `200` with the reason phrase
and no body.

## Annotations

All are on the handler class.

### @ApiOperation

```java
@GET("/users/{id}")
@ApiOperation(summary = "Get user", description = "Returns one user", tags = {"users"},
              deprecated = false, operationId = "getUser")
```

Optional. Empty values are omitted.

### @Parameter

Declares a parameter the code cannot show, such as a query parameter. Repeatable.

```java
@Parameter(name = "expand", in = ParameterIn.QUERY, type = SchemaType.STRING,
           description = "Related data to include", examples = {"roles", "permissions"})
```

| Attribute | Default |
|---|---|
| `name` | required |
| `in` | `QUERY`. Also `PATH`, `HEADER`, `COOKIE` |
| `type` | `STRING`. Also `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY` |
| `required`, `allowEmptyValue`, `explode` | `false` |
| `description`, `style`, `example`, `examples` | empty |

### @APIResponse

Declares a response. Repeatable. When any `@APIResponse` is present the success response is no longer
inferred, so declare it as well.

```java
@APIResponse(responseCode = "200", description = "User found",
             content = @Content(schema = UserDto.class, example = "{\"id\":\"usr-1\"}"))
@APIResponse(responseCode = "409", description = "That email is taken")
@APIResponse(responseCode = "204", description = "Deleted")
```

`@Content` has `schema` (a class), `array` (wrap the schema in an array) and `example` (a string).
With no `schema`, a `2xx` uses the inferred return type and a `4xx`/`5xx` uses the error object. With
nothing to infer, the response has no body. The media type is never on `@Content`: it is the
handler's.

`responseCode` must be an integer, otherwise the boot fails with `Invalid APIResponse.responseCode`.

### @RequestBody

For a handler that reads its body by hand, or to describe it as something else.

```java
@RequestBody(value = User.class, array = true, contentType = ContentType.JSON,
             required = true, description = "Users to store")
```

### @Consumes and @Produces

`@Consumes(ContentType.XML)` and `@Produces(ContentType.XML)` set the media types for a handler that
reads or writes its body itself. A handler with codecs in its signature needs neither. Both are
inherited.

### @Undocumented

Leaves the route out of the document. Inherited, so on a base class it excludes every handler that
extends it.

## Shared responses

If at least two operations have an identical response for the same status, it is written once under
`components.responses` (named after the reason phrase, for example `Unauthorized`) and referenced
with `$ref`. An operation that answers the same status differently keeps its own response.

Responses are sorted by status code.

## Media type resolution

For the answer: the output codec's content type, then `@Produces`, then JSON. For the request body:
the input codec's, then `@RequestBody.contentType` or `@Consumes`, then JSON. A request type of
`Void` or `Object` produces no request body.
