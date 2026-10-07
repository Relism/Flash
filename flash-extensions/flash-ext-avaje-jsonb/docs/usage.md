# Using Avaje Jsonb

## JsonHandler

```java
public abstract class JsonHandler<I, O> extends CodecRequestHandler<Json, I, Json, O> {}
```

`I` is the body type (`Void` for none) and `O` the answer. A body is parsed from the request stream
by the generated adapter. An answer declared as an interface or abstract class is written by the
adapter of its runtime type.

| Cause | Status |
|---|---|
| Malformed or truncated JSON | 400, `Invalid request body: <message>` |
| The installed validation engine rejects the value | 422 |

## Configuration

`new JsonModule()` uses `Jsonb.builder().build()`, which loads every adapter the generator wrote.
Pass your own for other settings:

```java
new JsonModule(Jsonb.builder().serializeNulls(true).failOnUnknown(true).build())
```

## Direct use

`Json` is a service: `read(request, type)`, `write(response, value)`, `contentType()` and `jsonb()`.

```java
final class Import extends RequestHandler {
    private final Json json;
    Import(Json json) { this.json = json; }

    @Override protected Object handle(Request req, Response res) throws Exception {
        return json.write(res, store(json.read(req, Manifest.class)));
    }
}
```

## Differences from flash-ext-jackson-json

- Types need `@Json` and the generator. Jackson reflects on anything.
- Jackson annotations have no effect. Use `@Json.Property`, `@Json.Ignore`, `@Json.SubType` and the
  rest of Jsonb's annotations.
- Jsonb's defaults are its own: nulls are omitted on write and unknown properties are skipped on
  read, unlike Jackson's defaults.
- `jsonb()` replaces `mapper()`. No `ObjectMapper` is provided.

## OpenAPI

With `flash-ext-openapi` present, each property appears under its wire name and properties Jsonb
does not write are left out. The names are read from the generated adapter, not from annotations.
