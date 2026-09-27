# flash-ext-avaje-jsonb

JSON bodies and JSON responses through [Avaje Jsonb](https://avaje.io/jsonb/): every type is bound
by an adapter generated at build time, so nothing is reflected on at runtime. A body is parsed
straight off the request stream into Jsonb's recycled buffers, and an answer is written into the
one array the response sends.

## Install

```java
App.create()
    .install(new JsonModule())
    .scan("com.acme.handlers");
```

The default `Jsonb` keeps the library's own defaults and loads every adapter the generator wrote;
`new JsonModule(jsonb)` takes one of your own:

```java
new JsonModule(Jsonb.builder().serializeNulls(true).failOnUnknown(true).build())
```

## What the application's build must do

The generator runs in the module that owns the types. Once a build sets `annotationProcessorPaths`
— Lombok's usually does — javac finds no processor on the classpath, so the generator is named,
not merely depended on. With `flash-parent` imported as a BOM its version comes from there
(maven-compiler-plugin 3.12 or later):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.avaje</groupId>
                <artifactId>avaje-jsonb-generator</artifactId>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

Then every type a handler reads or answers carries `@Json`; the types its fields hold get their
adapters without it. A type that cannot carry the annotation is listed with `@Json.Import`.

```java
@Json
public record NewUser(String name, Address address) {}
```

The generated types sit in a subpackage, so an annotated type must be visible from there: public,
and if nested, nested in a public type.

Adapters are found through `ServiceLoader`, one `META-INF/services/io.avaje.jsonb.spi.JsonbExtension`
per compiled module. **A shaded jar must merge them** — with more than one module generating, the
default shade keeps one file and the other modules' adapters are gone:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

## A handler

```java
@POST("/users")
public final class CreateUser extends JsonHandler<NewUser, User> {
    private final UserService users;

    public CreateUser(UserService users) { this.users = users; }

    @Override protected User handle(Request req, Response res, NewUser body) {
        return users.create(body);
    }
}
```

Both types are resolved when the application boots: one the generator never saw fails the boot,
naming it, instead of the first request. An answer declared as an interface or an abstract class
is left to its runtime type, since that is what is written.

A malformed or truncated body never reaches the handler (400), nor does one that breaks a rule of
the installed [validation engine](../flash-ext-validation-core) (422). `Void` as the first type is a
route that reads nothing.

## A handler that reads or writes it itself

```java
public final class Import implements Handler {
    private final Json json;

    public Import(Json json) { this.json = json; }

    @Override public Object handle(Request req, Response res) throws Exception {
        return json.write(res, archive.store(json.read(req, Manifest.class)));
    }
}
```

`Json` is the [`Codec`](../flash-ext-serde) for `application/json`: `read`, `write`,
`contentType`, and `jsonb()` for everything else. The same call is what a lambda route uses.

## Coming from flash-ext-jackson-json

The class names are the same — `Json`, `JsonHandler`, `JsonModule` — so a handler changes its
imports and nothing else. What differs is the library underneath:

- Types need `@Json` and the generator in the build; Jackson reflects on anything.
- Jsonb's defaults are its own: a null property is left out (`serializeNulls(false)`) where
  Jackson writes it, and an unknown property is skipped (`failOnUnknown(false)`) where Jackson
  refuses the body.
- Jackson's annotations mean nothing to Jsonb; its own are `@Json.Property`, `@Json.Ignore`,
  `@Json.SubType` and the rest of `@Json`'s family.
- `jsonb()` replaces `mapper()`, and nothing provides an `ObjectMapper`.
