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
| `consumes(ContentType)`, `produces(ContentType)` | Media types, taking priority over annotations |
| `requestType(Type)`, `responseType(Type)` | Types for the body schemas, taking priority over what `handle` returns |

`OpenApiResponseContribution.builder().description(...).header(name, schemaMap).build()` describes a
response. An explicit `@APIResponse` description always wins over a contributed one for the same
status. A status above 399 added by a contributor with no body gets the error object.

## What ships

| Module | Contributes |
|---|---|
| `flash-ext-openapi` itself | Request and response types and media types of `CodecRequestHandler`s |
| JSON, XML and Jsonb modules | Wire names, ignored fields, access |
| Validation engines | Bounds, formats, required |
| `flash-ext-protobuf-java`, `flash-ext-validation-protovalidate` | Message schemas and rules |
| `flash-ext-security-core` | Security schemes and requirements, `401` and `403` |
| `flash-ext-limiter` | Rate limit headers and `429` |
