# JacksonCodec

`JacksonCodec` implements the serde `Codec` over one `ObjectMapper` and one `ContentType`.

```java
public abstract class JacksonCodec implements Codec, Bound {
    protected JacksonCodec(ObjectMapper mapper, ContentType contentType)
    public ObjectMapper mapper()
}
```

## Reading

For each handler type, `decoder(type)` builds an `ObjectReader` once at boot with
`mapper.readerFor(mapper.constructType(type))`.

- A request body is parsed from `request.body().stream()` as it arrives, not read whole first.
- A message handed over as bytes (for example by a duplex protocol) is parsed from the array.
- A body that Jackson cannot parse is a `400` with the message `Invalid request body: <reason>` (or
  `Invalid message: <reason>` for bytes). It never reaches the handler.
- After parsing, the value goes to the installed `Validation` engine. A violation is a `422`.

## Writing

`encoder(type)` builds an `ObjectWriter` once at boot. It writes into the response's `ByteWriter`.
`write(response, value)` sets the content type and returns `mapper.writeValueAsBytes(value)`.

## Thread safety

An `ObjectMapper` is thread safe once configured. Configure it before handing it to the codec.

## Validation

`bind` looks up the `Validation` service with `services.find`. With none installed, the codec logs
one info line (`No validation engine installed: ... bodies are parsed and not checked`) and does not
validate. With two engines installed, `find` fails the boot.

## Using it directly

A handler that needs the format outside a typed signature takes the codec as a constructor
parameter, for example `Json` from `flash-ext-jackson-json`, then calls `read(request, Type)`,
`write(response, value)` or `mapper()`.
