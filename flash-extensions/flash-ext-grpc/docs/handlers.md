# Handlers

A gRPC method is a `POST` to `/<package>.<Service>/<Method>`. Annotate the handler with
`@Grpc("/orders.v1.Orders/Place")` and extend the class that matches the method in the `.proto`.

| `.proto` method | Handler |
|---|---|
| `rpc Place(PlaceReq) returns (PlaceReply)` | `GrpcUnary<PlaceReq, PlaceReply>` |
| `rpc Watch(WatchReq) returns (stream Event)` | `GrpcServerStreaming<WatchReq, Event>` |
| `rpc Upload(stream Chunk) returns (Summary)` | `GrpcClientStreaming<Chunk, Summary>` |
| `rpc Talk(stream Msg) returns (stream Msg)` | `GrpcBidiStreaming<Msg, Msg>` |

All four extend `GrpcHandler<I, O>`, a sealed class, so no other shape exists. `I` and `O` are
generated protobuf messages.

## Unary

```java
@Grpc("/orders.v1.Orders/Place")
final class Place extends GrpcUnary<PlaceReq, PlaceReply> {
    @Override protected PlaceReply handle(Request request, PlaceReq input) throws Exception { ... }
}
```

Exactly one request message is required: none is `INVALID_ARGUMENT` (`missing request message`) and a
second is `INVALID_ARGUMENT` (`too many request messages`).

## Server streaming

```java
@Grpc("/orders.v1.Orders/Watch")
final class Watch extends GrpcServerStreaming<WatchReq, Event> {
    @Override protected void handle(Request request, WatchReq input, GrpcOutput<Event> output) throws Exception {
        for (Event event : events.since(input.getCursor())) output.send(event);
    }
}
```

`GrpcOutput.send` writes and flushes one message. It is safe to call from another thread, and throws
`IOException` once the call has ended.

## Client streaming

```java
@Grpc("/orders.v1.Orders/Upload")
final class Upload extends GrpcClientStreaming<Chunk, Summary> {
    @Override protected Summary handle(Request request, GrpcInput<Chunk> input) throws Exception {
        Summary.Builder result = Summary.newBuilder();
        for (Chunk chunk; (chunk = input.next()) != null; ) result.addSizes(chunk.getData().size());
        return result.build();
    }
}
```

`GrpcInput.next()` blocks for the next message and returns `null` after the client half-closes.

## Bidirectional streaming

```java
@Grpc("/orders.v1.Orders/Talk")
final class Talk extends GrpcBidiStreaming<Msg, Msg> {
    @Override protected void handle(Request request, GrpcInput<Msg> input, GrpcOutput<Msg> output) throws Exception {
        for (Msg message; (message = input.next()) != null; ) output.send(message);
    }
}
```

Reading and writing may happen on different threads.

## Request metadata and services

Read metadata with `request.header(name)`. Handlers are built like any other
handler, so services come through the constructor. Middleware registered with `use` or on the route
runs before the call starts and sees the request like any HTTP request. It may add response headers.
A middleware that throws an `HttpException` refuses the call with an ordinary HTTP error response,
before any gRPC message is sent.

A handler can be registered without scanning: `app.route(HttpMethod.POST, "/orders.v1.Orders/Place", Place.class)`.
