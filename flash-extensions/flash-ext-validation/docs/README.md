# flash-ext-validation

Request validation for Flash. Standard `jakarta.validation` annotations, compiled once per type
into a flat check table, with zero allocation on the passing path.

## What it provides

| Component | Description |
|---|---|
| `Validation` | The service — `body(req, type)` parses and verifies, `validate(value)` verifies |
| `Validator` | One type's compiled constraints; reusable and thread-safe |
| `ValidationException` | 422 carrying every violation, not just the first |

## Dependency

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-validation</artifactId>
    <version>${flash.version}</version>
</dependency>
```

## Quick start

```java
FlashApp.create(8080)
    .install(new JacksonExtension())
    .install(new ValidationExtension())
    .scan("dev.example.api");
```

```java
public record CreateUser(
        @NotBlank @Size(max = 80) String name,
        @Email                    String email,
        @Min(18)                  int    age) {}
```

```java
@POST("/api/users")
public final class CreateUserHandler extends RequestHandler {

    private Validation validation;
    private UserService users;

    @Override protected void onInit() {
        validation = require(Validation.class);
        users      = require(UserService.class);
    }

    @Override public Object handle(Request req, Response res) throws Exception {
        CreateUser dto = validation.body(req, CreateUser.class);
        return res.status(201).body(users.create(dto));
    }
}
```

There is nothing to configure. Constraints come from the annotations already on your types, and
failures reach the client as `422` on their own — see [Error responses](#error-responses).

## Supported constraints

`@NotNull` · `@NotBlank` · `@NotEmpty` · `@Size` · `@Min` · `@Max` · `@Email` · `@Pattern`

Jakarta null semantics are honoured exactly: **only `@NotNull` rejects null**. Every other
constraint passes a null value, so `@Email String email` means "if present, must look like an
email" — combine with `@NotNull` when it is mandatory.

`@Size` applies to `CharSequence`, `Collection`, `Map` and object arrays. `@Min`/`@Max` apply to
primitive integrals and to `Number` subtypes.

An unsupported annotation is ignored rather than rejected, so adding one is never a boot failure.

## Records and classes

Constraints are read from **declared fields**. A constraint on a record component propagates to
its backing field, so records and plain classes take the same path with no extra configuration:

```java
record CreateUser(@NotBlank String name) {}          // works
class  CreateUser { @NotBlank private String name; } // works
```

## Error responses

`ValidationException` extends Flash's `HttpException` with status 422, so the default exception
handler renders it. Nothing is registered, and your own `onException` still wins if you set one.

```json
{"error":"name must not be blank; age must be at least 18","status":422}
```

Malformed JSON is a different failure and comes back as `400` from the codec, before any
constraint runs.

## OpenAPI

Install `flash-ext-openapi` alongside and the generated schema mirrors the same annotations —
`minLength`, `maxLength`, `minItems`, `minimum`, `maximum`, `pattern`, `format: email`, and
`required`. Declared once, enforced and published.

Nothing registers this. `flash-ext-openapi` carries `jakarta.validation-api` as an optional
dependency and detects it at boot; without it the bridge class is never loaded.

An explicit `@Schema` always wins — the bridge only fills keys nobody set.

## Without Jackson

`flash-ext-jackson` is optional. Without it `validate(value)` still works on values you construct
or parse yourself; only `body(req, type)` needs a codec and says so if one is missing.

## Performance

The passing path is the one that runs on every request, so it allocates nothing:

- **Compiled once per type.** Constraints resolve to an opcode plus operands at first use, cached
  in a `ClassValue` — stored beside the class by the JVM, so no map lookup, no lock, and the entry
  is collected with the class rather than pinning it.
- **No reflection per request.** Fields are read through `MethodHandle`s adapted to an exact
  signature: `(Object)Object` for references, `(Object)long` for primitive integrals. `invokeExact`
  neither boxes nor builds the argument array that `Field.get` and `Method.invoke` allocate.
- **No megamorphic dispatch.** Checks are a flat array walked by a `tableswitch` on an opcode, not
  a class hierarchy behind a virtual call.
- **No copies.** `@Size` reads a length the object already knows; `@Email` scans with `indexOf`
  rather than a regex, because `Pattern.matcher` allocates a matcher and two int arrays per call.
- **Messages pre-rendered at compile time**, so even a failure formats nothing.

The list, the violations and the exception exist only once something fails.

`@Pattern` is the deliberate exception: its regex is compiled once, but `matcher()` allocates per
call. It is marked in the source. Prefer `@Size`/`@Email` on hot routes, or validate the shape
structurally.

## Pre-warming

Compilation happens on a type's first request. To pay it at boot instead:

```java
ctx.onReady(() -> ctx.require(Validation.class).forType(CreateUser.class));
```

Worth it only for a route that must not pay first-call cost. Everything else warms itself.
