# flash-ext-serde

The typed serialization contract that format extensions share. It defines `Codec`, `Decoder`,
`Encoder` and two handler bases that name their codecs and message types in their generic
signature. It contains no format. Applications install a format module (JSON, XML, protobuf) and
use its handler alias.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-serde</artifactId>
</dependency>
```

```java
final class Convert extends CodecRequestHandler<Xml, Order, Json, Receipt> {
    @Override protected Receipt handle(Request request, Response response, Order order) {
        return new Receipt(order.id());
    }
}
```

| Page | Content |
|---|---|
| [docs/handlers.md](docs/handlers.md) | `CodecRequestHandler`, `CodecDuplexHandler`, type resolution |
| [docs/codecs.md](docs/codecs.md) | The `Codec` contract and how to write one |
