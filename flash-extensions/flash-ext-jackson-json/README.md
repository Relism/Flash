# flash-ext-jackson-json

JSON bodies and JSON responses.

## Install

```java
JsonExtension json = new JsonExtension();

FlashApp.create(8080)
    .install(json)
    .use(json.auto())
    .scan("com.acme.handlers")
    .startAndBlock();
```

The default mapper discovers the modules on the classpath (Java Time among them) and writes dates
as ISO strings; `new JsonExtension(mapper)` takes one of your own. `auto()` serializes whatever a
handler returns, leaving alone what is already a response: `null`, a `Response`, a `byte[]` or a
`CharSequence`.

## A handler with a body

The body type is the handler's type argument, and that is the whole declaration — it is also what
the OpenAPI document describes and what the constraints are read from:

```java
@POST("/users")
public final class CreateUser extends JsonHandler<NewUser> {
    @Inject private UserService users;

    @Override protected Object handle(Request req, Response res, NewUser body) {
        return users.create(body);
    }
}
```

A malformed body never reaches it (400), nor does one that breaks a constraint (422).

## A handler that reads it itself

```java
public final class Import extends RequestHandler {
    @Inject private Json json;

    @Override public Object handle(Request req, Response res) throws Exception {
        return archive.store(json.body(req, Manifest.class));
    }
}
```

`Json` is the [`Codec`](../flash-ext-jackson-core) for `application/json`: `body`, `write`,
`writeView`, `mapper`.

## Notes

- One mapper per application (or per scope), shared by every handler; `ObjectMapper` is
  thread-safe once configured.
- Install `flash-ext-jackson-xml` beside this one when an application speaks both: a route picks
  its format by the handler it extends, not by negotiation.
