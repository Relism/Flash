# JsonHandler

```java
public abstract class JsonHandler<I, O> extends CodecRequestHandler<Json, I, Json, O> {}
```

`I` is the request body type, `O` the answer type. Use `Void` as `I` for a route that reads no body.
See [flash-ext-serde](../../flash-ext-serde/docs/handlers.md) for what the base does at boot and per
request.

```java
@GET("/users")
final class ListUsers extends JsonHandler<Void, List<User>> {
    private final UserService users;
    ListUsers(UserService users) { this.users = users; }

    @Override protected List<User> handle(Request request, Response response, Void ignored) {
        return users.all();
    }
}
```

Answers have `Content-Type: application/json`.

## Failures

| Cause | Status |
|---|---|
| Body is not valid JSON for `I`, or truncated | 400, message `Invalid request body: <jackson message>` |
| A [validation engine](../../flash-ext-validation-core/README.md) rejects `I` | 422, listing every broken rule |

Neither reaches `handle`. Both are `HttpException`s, so the default error handler answers them as
`{"error": "...", "status": ...}`.

## Without a typed handler

`Json` is also a service, so a plain `RequestHandler` can use it:

```java
final class Import extends RequestHandler {
    private final Json json;
    private final Archive archive;
    private Decoder manifests;
    Import(Json json, Archive archive) { this.json = json; this.archive = archive; }

    @Override protected void onInit() { manifests = Decoder.of(json, Manifest.class, services()); }

    @Override protected Object handle(Request req, Response res) throws Exception {
        return json.write(res, archive.store((Manifest) manifests.decode(req)));
    }
}
```

`Decoder.of` parses and validates like a typed handler, with a reader prepared once.
`write(response, value)` sets `Content-Type: application/json` and returns the bytes.

## Mixing formats

A handler can read one format and write another by naming both codecs:

```java
final class Convert extends CodecRequestHandler<Xml, Order, Json, Receipt> { ... }
```

Install both modules. See `flash-ext-jackson-xml`.
