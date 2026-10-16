# gRPC-Web

Every `@Grpc` route also answers gRPC-Web, the variant browsers can speak. Nothing is installed for
it: the request's content type selects it (see [protocol.md](protocol.md#request-checks)). It runs
over HTTP/1.1 and HTTP/2.

```java
App.create()
    .install(new ProtobufModule(), new GrpcModule(OrdersProto.getDescriptor()))
    .use(GrpcWeb.cors("https://app.example"))
    .scan("com.acme.orders");
```

## Call shapes

| Handler | gRPC-Web |
|---|---|
| `GrpcUnary` | Answered |
| `GrpcServerStreaming` | Answered, each `send` flushed |
| `GrpcClientStreaming`, `GrpcBidiStreaming` | `UNIMPLEMENTED` before the handler runs, `gRPC-Web carries unary and server-streaming calls` |

The protocol does not define client streaming, and its bidirectional streaming is pending.

## Status

| Call | Where the status goes |
|---|---|
| At least one message sent | A last frame with flag `0x80` whose payload is `grpc-status: N\r\n`, then `grpc-message: ...\r\n` for a failure. Names are lowercase |
| No message sent | Trailers-only: `grpc-status` and `grpc-message` are response headers and the body is empty |

The HTTP status is `200` in both cases. The reply of a unary call and its status frame leave in one
write. A failure that happens before the handshake completes, such as a `401` from security or a
`415`, is a plain HTTP answer.

## Text mode

`application/grpc-web-text` carries the same frames in base64.

- A request in text mode is decoded as it is read. Padding may end any four-character group, so a
  body made of padded chunks is accepted. A character outside the base64 alphabet is
  `INVALID_ARGUMENT`; a body that ends inside a group fails the call with an `EOFException`.
- The answer is in text mode when the request was, or when a binary request's `Accept` contains
  `application/grpc-web-text`. Each write is one padded base64 chunk: a server-streaming message, the
  reply with its status frame.

Messages are read in place only in binary mode.

## CORS

A browser calls from another origin only after a preflight. `GrpcWeb.cors` returns a
[`Cors`](../../../flash/docs/middleware.md#cors) set up for gRPC-Web:

| Setting | Value |
|---|---|
| Methods | `POST` |
| Allowed headers | `content-type`, `grpc-timeout`, `x-grpc-web`, `x-user-agent` |
| Exposed headers | `grpc-status`, `grpc-message`, `grpc-status-details-bin` |

| Method | Origins |
|---|---|
| `GrpcWeb.cors(String... origins)` | The named ones; none named means any |
| `GrpcWeb.cors(List<String> origins, String... headers)` | The named ones, allowing `headers` on top of the gRPC-Web ones |

```java
app.use(GrpcWeb.cors(List.of("https://app.example"), "authorization"));
```

`Cors` answers the preflight before routing, so it never reaches authentication. The credential of
the call itself travels in a header, as on gRPC.

## Clients

Any gRPC-Web client works, for example `@connectrpc/connect-web` in a browser or
`@connectrpc/connect-node` with `createGrpcWebTransport`. A client in text mode must decode
concatenated padded chunks, as the protocol requires.
