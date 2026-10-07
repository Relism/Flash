# Writing a codec

A codec is one serialization format. A module provides one by registering a `Codec` service.

```java
public interface Codec {
    Decoder decoder(Type type);
    Encoder encoder(Type type);
    Object write(Response response, Object value) throws Exception;
    ContentType contentType();
    default <T> T read(Request request, Type type) throws Exception { ... }
}
```

| Method | Contract |
|---|---|
| `decoder(type)` | Called once per handler at boot. Returns a `Decoder` bound to `type`. Fail here for a type the format cannot handle |
| `encoder(type)` | Called once per handler at boot. Returns an `Encoder` bound to `type` |
| `write(response, value)` | Sets the response content type and returns the body bytes. For handlers that write directly |
| `contentType()` | Media type the codec reads and writes. OpenAPI uses it |
| `read(request, type)` | Decodes a request body of an arbitrary type. The default builds a decoder every call; override it if you use it often |

```java
public interface Decoder {
    Object decode(byte[] bytes, int offset, int length) throws Exception;
    default Object decode(Request request) throws Exception { ... }   // whole body by default
}

public interface Encoder {
    void encode(Object value, ByteWriter output) throws Exception;
    ContentType type();
}
```

`decode(Request)` is the request-body entry; override it to parse from `request.body().stream()`
without holding the body. `decode(bytes, offset, length)` is used where a protocol delivers a message
in a buffer, such as a gRPC frame. `Encoder.encode` appends to a `ByteWriter`.

## Conventions

- Prepare everything per type in `decoder` and `encoder`. They run at boot, so work such as
  building a reader or resolving an adapter costs nothing per request.
- Throw `HttpException.badRequest(...)` for a body the format rejects.
- After decoding a request, call the installed `Validation` engine if there is one, so a rule
  violation is a 422. Look it up in `bind(Services)` by implementing `Bound`:

  ```java
  @Override public void bind(Services services) {
      validation = services.find(Validation.class).orElse(null);
  }
  ```

  This is what the JSON, XML and protobuf codecs do. See
  [flash-ext-validation-core](../../flash-ext-validation-core/README.md).
- Register the codec with `app.add(MyCodec.class, instance)` in a `Module`. Handlers find it by
  its class, so it is the class in the handler's generic signature.
- To describe your format's wire names and rules in an OpenAPI document, add an `OpenApiContributor`
  through `Module.ifPresent`, as the JSON modules do.
