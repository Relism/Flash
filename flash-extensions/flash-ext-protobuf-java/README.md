# flash-ext-protobuf-java

Binary Protocol Buffers over HTTP bodies, through Google's generated Java messages. Installs the
`Protobuf` codec and provides `ProtobufHandler<I, O>` with `Content-Type: application/protobuf`.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-protobuf-java</artifactId>
</dependency>
```

```java
App app = App.create().install(new ProtobufModule()).scan("com.acme.api");

@POST("/orders")
final class PlaceOrder extends ProtobufHandler<PlaceReq, PlaceReply> {
    @Override protected PlaceReply handle(Request request, Response response, PlaceReq body) {
        return PlaceReply.newBuilder().setId(7).build();
    }
}
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Handlers, codec behavior, extensions, validation |
| [docs/openapi.md](docs/openapi.md) | How messages appear in the OpenAPI document |
