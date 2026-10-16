# Protocol behavior

## Request checks

The request's `Content-Type`, without parameters and in any case, chooses the wire:

| Content type | Wire | HTTP version | Response content type |
|---|---|---|---|
| `application/grpc`, `application/grpc+proto` | gRPC | HTTP/2 only, `505` `gRPC runs over HTTP/2` otherwise | `application/grpc` |
| `application/grpc-web`, `application/grpc-web+proto` | gRPC-Web | any | `application/grpc-web+proto` |
| `application/grpc-web-text`, `application/grpc-web-text+proto` | gRPC-Web, base64 | any | `application/grpc-web-text+proto` |
| anything else, `+json` included | | | `415` |

A binary gRPC-Web request whose `Accept` contains `application/grpc-web-text` gets a base64 answer.
gRPC-Web is described in [grpc-web.md](grpc-web.md).

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

A call ends with `grpc-status` and, for a failure, `grpc-message` (percent-encoded). On gRPC they
are HTTP trailers; on gRPC-Web they travel in the body or the headers, as
[grpc-web.md](grpc-web.md#status) describes. The status is chosen from how the handler ended:

| Handler outcome | Status |
|---|---|
| Returns normally | `OK` (0) |
| Throws `GrpcException(status, message)` | That status and message |
| Throws `HttpException` 400 or 422, including validation failures and malformed protobuf | `INVALID_ARGUMENT` |
| Throws `HttpException` 401, 403, 404 | `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND` |
| Throws `HttpException` 409, 412, 429 | `ABORTED`, `FAILED_PRECONDITION`, `RESOURCE_EXHAUSTED` |
| Throws `HttpException` 501, 503, 504 | `UNIMPLEMENTED`, `UNAVAILABLE`, `DEADLINE_EXCEEDED` |
| Any other `HttpException` | `INTERNAL`, with the exception's message |
| Anything else | `INTERNAL`, no message. Logged as an error, or at debug level when the cause is an I/O error from the transport |

The exception is found by walking the cause chain. After the trailers are written the handler
throws `AnsweredFailure`, so a scope around it fails too: a `@Transactional` handler rolls back. `Status` lists the 17 gRPC codes, and
`ordinal()` is the wire value.

```java
throw new GrpcException(Status.NOT_FOUND, "order 7");
```

## Not implemented

Compression, server reflection, health checking, deadline handling and a client. A client
that resets the stream interrupts the next read or write.
