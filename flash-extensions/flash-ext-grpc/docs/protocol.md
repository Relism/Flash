# Protocol behavior

## Request checks

Before a handler runs, the handshake checks:

| Condition | Answer |
|---|---|
| The request is not HTTP/2 | `505`, `gRPC runs over HTTP/2` |
| `Content-Type` is not `application/grpc`, `application/grpc+proto` or starts `application/grpc;` | `415` |

The response carries `Content-Type: application/grpc`.

## Framing

Each message is a length-prefixed frame: one byte of compression flag and a four-byte big-endian
length, then the protobuf message.

- A frame whose compression flag is set is `UNIMPLEMENTED` (`compressed messages are not supported`).
  Compression is not implemented.
- A message above 4 MiB is `RESOURCE_EXHAUSTED`.
- A request that ends in the middle of a message fails the call with an `EOFException`.

A message that arrived in one piece inside the connection buffer is decoded in place. A split message
is gathered once.

## Status

A call ends with `grpc-status` and, for a failure, `grpc-message` (percent-encoded) as trailers.
The status is chosen from how the handler ended:

| Handler outcome | Status |
|---|---|
| Returns normally | `OK` (0) |
| Throws `GrpcException(status, message)` | That status and message |
| Throws `HttpException` 400 or 422, including validation failures and malformed protobuf | `INVALID_ARGUMENT` |
| Throws `HttpException` 404 | `NOT_FOUND` |
| Any other `HttpException` | `INTERNAL`, with the exception's message |
| Anything else | `INTERNAL`, no message. Logged as an error, or at debug level when the cause is an I/O error from the transport |

The exception is found by walking the cause chain. `Status` lists the 17 gRPC codes, and
`ordinal()` is the wire value.

```java
throw new GrpcException(Status.NOT_FOUND, "order 7");
```

## Not implemented

Compression, server reflection, gRPC-Web, health checking, deadline handling and a client. A client
that resets the stream interrupts the next read or write.
