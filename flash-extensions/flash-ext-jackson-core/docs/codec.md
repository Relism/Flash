# JacksonCodec

`JacksonCodec` implements the serde `Codec` over one `ObjectMapper` and one `ContentType`.

```java
public abstract class JacksonCodec implements Codec {
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
- The codec only parses. A validation engine checks what it parsed through serde's `Decoder.of`;
  see [codecs.md](../../flash-ext-serde/docs/codecs.md#checks).

## Writing

`encoder(type)` builds an `ObjectWriter` once at boot. It writes into the response's `ByteWriter`.
`write(response, value)` sets the content type and returns `mapper.writeValueAsBytes(value)`.

## Thread safety

An `ObjectMapper` is thread safe once configured. Configure it before handing it to the codec.

## Field names

`wireName(field)` answers with the name the mapper reads or writes the field under, the written one
first, and `null` for a field it does neither for: `@JsonIgnore`, left out by
`@JsonIgnoreProperties` or `@JsonIncludeProperties`, or not visible. Worked out once per class.

## Using it directly

A handler that needs the format outside a typed signature takes the codec as a constructor
parameter, for example `Json` from `flash-ext-jackson-json`. It parses through
`Decoder.of(codec, type, services())`, prepared in `onInit`, and answers with `write(response, value)`
or `mapper()`.
