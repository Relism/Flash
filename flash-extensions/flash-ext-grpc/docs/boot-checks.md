# Boot checks

`new GrpcModule(OrdersProto.getDescriptor(), ...)` takes the protobuf file descriptors of the
services the app serves. The descriptors are named explicitly because a service usually lives in a
file that only imports its messages, so they cannot be found from the messages reliably.

For every route Flash builds, `GrpcModule` checks the handler against the descriptor and fails the
boot with a message naming the handler and the proto method when:

- the handler class carries `@Grpc` but is not a `GrpcHandler`,
- the route path matches no method of a registered service (the message tells you to register the
  file's descriptor),
- the handler's base class does not match the method's `client_streaming` and `server_streaming`
  flags,
- either message type differs from the method's input or output type,
- the codec arguments of the handler are not `Protobuf`.

A method of a registered service that no route answers is logged as a warning when the app starts:
`gRPC method /orders.v1.Orders/Cancel has no @Grpc handler`.

Because a handler's call shape is part of its class, a mismatch with the `.proto` is a startup
error, not a runtime one.
