# OpenApiContributor

OpenAPI does not know the other modules. They add what they know through `OpenApiContributor`,
registered as a service. All contributors are read when the app starts.

```java
app.add(OpenApiContributor.class, MyDocs.class);
```

A module that should work without `flash-ext-openapi` registers its contributor only when the class
is present:

```java
app.install(Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
        () -> a -> a.add(OpenApiContributor.class, MyDocs.class)));
```

Every method has a default that contributes nothing:

| Method | Purpose |
|---|---|
| `schema(Type)` | Describe a type completely. Return `null` to leave it to the next contributor and then the default model. The first non-null answer wins |
| `used(Type)` | Told about every type a schema is asked for, so a contributor that only adds to others' schemas knows which types are in the document |
| `componentContributions()` | Fragments merged into `components`. Maps merge deeply; lists are concatenated without duplicates; for other values the last contributor wins |
| `operationFor(Class<?> handler)` | An `OpenApiOperationContribution` for one handler class |
| `parameters(Type, Function<Field, String> names)` | The contribution (parameters, responses) of an input of that type that an operation reads from the request, as another contributor's `requestParameters` names it. `names` gives a field its name in the document, `null` for one left out. Return `null` to leave the type to the next; the first non-null wins |
| `name(Field)` | A field's wire name, or `null`. The first non-null wins |
| `ignored(Field)` | Whether a field is left out. Any contributor can exclude |
| `property(Field, Map schema)` | Add keywords to a property's schema. Return `true` when it is required |

## OpenApiOperationContribution

Built with `OpenApiOperationContribution.builder()`:

| Method | Adds |
|---|---|
| `security(scheme, scopes)` | One security alternative to the operation. Additive |
| `response(status, OpenApiResponseContribution)` | A response for that status, or headers and a description merged into an existing one |
| `allResponses(OpenApiResponseContribution)` | Headers merged into every response of the operation |
| `consumes(ContentType)`, `produces(ContentType)` | Media types, taking priority over annotations. Either one also makes a `DuplexHandler` route documented |
| `requestType(Type)`, `responseType(Type)` | Types for the body schemas, taking priority over what `handle` returns |
| `requestParameters(Type)` | A type the operation reads from the request itself rather than a body. The contributors are asked for its `parameters` |
| `parameter(Map)` | A parameter object (`name`, `in`, `schema`, ...), listed after the path's and the annotations' |

`OpenApiResponseContribution.builder().description(...).header(name, schemaMap).build()` describes a
response. An explicit `@APIResponse` description always wins over a contributed one for the same
status. A status above 399 added by a contributor with no body gets the error object.

## What ships

| Module | Contributes |
|---|---|
| `flash-ext-serde`, through `Codec.module` | Request and response types and media types of `CodecRequestHandler`s, a `@FromRequest` input as `requestParameters`, and each field's wire name or its absence, from `Codec.wireName` |
| Jackson JSON and XML modules | Read-only, write-only and required, from `@JsonProperty` |
| Validation engines | Bounds, formats, required |
| `flash-ext-protobuf-java`, `flash-ext-validation-protovalidate` | Message schemas and rules |
| `flash-ext-security-core` | Security schemes and requirements, `401` and `403` |
| `flash-ext-limiter` | Rate limit headers and `429` |
| `flash-ext-ndjson` | `application/x-ndjson` bodies of `NdjsonHandler`s, with the schema of one line |
| `flash-ext-data-core` | The `size`, `sort` and `after` parameters and the `400` of an operation that reads a `PageRequest` |
