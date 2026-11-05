# Writing a codec

A codec is one serialization format. A module installs one through `Codec.module`.

```java
public interface Codec {
    Decoder decoder(Type type);
    Encoder encoder(Type type);
    Object write(Response response, Object value) throws Exception;
    ContentType contentType();
    default String wireName(Field field) { return field.getName(); }

    static <C extends Codec> Module module(Class<C> type, C codec) { ... }
}
```

| Method | Contract |
|---|---|
| `decoder(type)` | Called once per handler at boot. Returns a `Decoder` bound to `type`. Fail here for a type the format cannot handle |
| `encoder(type)` | Called once per handler at boot. Returns an `Encoder` bound to `type` |
| `write(response, value)` | Sets the response content type and returns the body bytes. For handlers that write directly |
| `contentType()` | Media type the codec reads and writes. OpenAPI uses it |
| `wireName(field)` | The name a field has in this format, or `null` when the format does not carry it. Asked at boot only, by whatever matches names a client sends (paging) and by the published document. The Jackson codecs answer as the mapper reads and writes the field (`@JsonProperty`, the naming strategy, `@JsonIgnore`, `@JsonIgnoreProperties`, `@JsonIncludeProperties`), the Avaje codec as its generated adapter does |
| `module(type, codec)` | Adds the codec under `type`, which handlers name in their signature, and as a `Codec`. With flash-ext-openapi on the classpath, it also documents every `CodecRequestHandler` from its signature |

```java
public interface Decoder {
    Object decode(byte[] bytes, int offset, int length) throws Exception;
    default Object decode(Request request) throws Exception { ... }   // whole body by default

    static Decoder of(Codec codec, Type type, Services services) { ... }
}

public interface Encoder {
    void encode(Object value, ByteWriter output) throws Exception;
    ContentType type();
}
```

`decode(Request)` is the request-body entry; override it to parse from `request.body().stream()`
without holding the body. `decode(bytes, offset, length)` is used where a protocol delivers a message
in a buffer, such as a gRPC frame. `Encoder.encode` appends to a `ByteWriter`.

## Checks

A codec only parses. Every protocol takes its decoder through `Decoder.of(codec, type, services)`,
which hands each decoded value to the installed `Check` services that check `type`:

```java
public interface Check {
    Consumer<Object> of(Type type);   // at boot, once per decoded type; null when nothing is checked
}
```

When no `Check` answers for a type, `Decoder.of` returns the codec's own decoder and a request pays
nothing. A validation engine's module adds one; see
[flash-ext-validation-core](../../flash-ext-validation-core/README.md). The typed handlers, NDJSON
and gRPC all decode through `Decoder.of`. A plain handler that parses a body itself does the same,
once, in `onInit`:

```java
final class Import extends RequestHandler {
    private final Json json;
    private Decoder manifests;
    Import(Json json) { this.json = json; }

    @Override protected void onInit() { manifests = Decoder.of(json, Manifest.class, services()); }

    @Override protected Object handle(Request req, Response res) throws Exception {
        return json.write(res, store((Manifest) manifests.decode(req)));
    }
}
```

## Conventions

- Prepare everything per type in `decoder` and `encoder`. They run at boot, so work such as
  building a reader or resolving an adapter costs nothing per request.
- Throw `HttpException.badRequest(...)` for a body the format rejects.
- Install the codec with `app.install(Codec.module(MyCodec.class, instance))` in a `Module`.
  Handlers find it by its class, so it is the class in the handler's generic signature.
- Answer `wireName` for every field, and `null` for one the format leaves out: the document and
  paging take names from it, so a format describes its fields once.
