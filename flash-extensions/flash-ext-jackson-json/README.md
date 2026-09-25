# flash-ext-jackson-json

JSON bodies and JSON responses.

## Install

```java
FlashApp.create(8080)
    .install(new JsonExtension())
    .scan("com.acme.handlers")
    .startAndBlock();
```

The default mapper discovers the modules on the classpath (Java Time among them) and writes dates
as ISO strings; `new JsonExtension(mapper)` takes one of your own.

## A handler

Both types are in the signature, and that is the whole declaration — it is also what the OpenAPI
document describes and what the constraints are read from:

```java
@POST("/users")
public final class CreateUser extends JsonHandler<NewUser, User> {
    @Inject private UserService users;

    @Override protected User handle(Request req, Response res, NewUser body) {
        return users.create(body);
    }
}
```

A malformed body never reaches it (400), nor does one that breaks a constraint (422). The answer is
written by the codec, so nothing has to be installed for a route to reply in JSON.

`Void` as the first type is a route that reads nothing:

```java
@GET("/users")
public final class ListUsers extends JsonHandler<Void, List<User>> {
    @Inject private UserService users;

    @Override protected List<User> handle(Request req, Response res, Void nothing) {
        return users.all();
    }
}
```

## A handler that reads or writes it itself

```java
public final class Import extends RequestHandler {
    @Inject private Json json;

    @Override public Object handle(Request req, Response res) throws Exception {
        return json.write(res, archive.store(json.read(req, Manifest.class)));
    }
}
```

`Json` is the [`Codec`](../flash-ext-serde) for `application/json`: `read`, `write`,
`contentType`, `mapper`. The same call is what a lambda route uses.

## Notes

- One mapper per application (or per scope), shared by every handler; `ObjectMapper` is
  thread-safe once configured.
- Install `flash-ext-jackson-xml` beside this one when an application speaks both: a route picks
  its formats by the codecs in its signature, not by negotiation, and the two ends may differ —
  `CodecHandler<Xml, Order, Json, Receipt>` reads XML and answers JSON.
