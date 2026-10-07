# flash-ext-grpc

gRPC over Flash's HTTP/2 server, with protobuf messages and one handler class per call shape.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-grpc</artifactId>
</dependency>
```

```java
App app = App.create()
        .install(new ProtobufModule(), new GrpcModule(OrdersProto.getDescriptor()))
        .scan("com.acme.orders");

@Grpc("/orders.v1.Orders/Place")
final class Place extends GrpcUnary<PlaceReq, PlaceReply> {
    @Override protected PlaceReply handle(Request request, PlaceReq input) {
        return PlaceReply.newBuilder().setId(7).build();
    }
}
```

HTTP/2 must be enabled on the server. See [config](../../flash/docs/config.md#protocol).

| Page | Content |
|---|---|
| [docs/handlers.md](docs/handlers.md) | The four handler classes and their cardinalities |
| [docs/protocol.md](docs/protocol.md) | Framing, status mapping, limits |
| [docs/boot-checks.md](docs/boot-checks.md) | What `GrpcModule` verifies at startup |
