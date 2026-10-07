# HTTP/1.1

`Http1Connection` runs the keep-alive loop of one connection: read a request, dispatch it through the
router, write the response, repeat. `RequestParser` reads requests in place from the connection's
buffer, and `Http1ResponseWriter` writes responses.

## Request loop

```mermaid
flowchart TD
    A[wait for first byte<br/>idleKeepAliveTimeoutMs] --> B[parse head<br/>headerReadTimeoutMs]
    B -->|malformed| X[write status line and close]
    B --> C[dispatch<br/>bodyReadTimeoutMs for the body]
    C --> D[write response]
    D --> E[discard unread body]
    E -->|keep-alive| A
    E -->|close| Z[close]
```

- Pipelined requests already in the buffer are served without waiting for new bytes.
- When the idle timeout expires, the connection closes silently.
- A connection stays open if the request is HTTP/1.1 without `Connection: close`, or HTTP/1.0 with
  `Connection: keep-alive`. `Connection` is read as a token list, so `keep-alive, Upgrade` is understood.
- During [shutdown](transport.md#shutdown) the response in progress is sent with `Connection: close`.

## Request rules

The parser rejects a request before routing when it breaks one of these rules. The answer is a
minimal `text/plain` response with the status and a message, and the connection is closed.

| Condition | Status |
|---|---|
| Missing or malformed method, target or protocol on the request line | 400 |
| Method not one of `GET POST PUT DELETE PATCH OPTIONS HEAD TRACE CONNECT PURGE QUERY` | 501 |
| Request line over 8,192 bytes | 431 |
| Head over [`maxHeaderBufferSize`](config.md#limits) | 431 |
| More than 100 header lines | 431 |
| Header name over 256 bytes, or value over 8,192 bytes | 431 |
| Header name with a character that is not a token character, or a line without `:` | 400 |
| Obsolete line folding (a line starting with space or tab) | 400 |
| A bare `CR` not followed by `LF` | 400 |
| Invalid `Content-Length` (empty, non-digit, over 19 digits, overflow) | 400 |
| Several `Content-Length` lines with different values | 400 |
| `Content-Length` and `Transfer-Encoding` together | 400 |
| `Transfer-Encoding` whose last coding is not `chunked` | 501 |
| `Content-Length` above [`maxBodySize`](config.md#limits) | 413 |

Rejecting ambiguous message boundaries and closing afterwards is deliberate: it removes the room that
request smuggling needs.

## Chunked bodies

A `Transfer-Encoding` whose final coding is `chunked` is decoded as the handler reads. Limits, each
answered with the status shown, thrown from the read:

| Limit | Value | Status |
|---|---|---|
| Bytes in one chunk | 16 MiB | 413 |
| Chunks per body | 100,000 | 413 |
| Total body | `maxBodySize` | 413 |
| Chunk size line | 16 hex digits, extensions 256 bytes | 400 |
| Trailer lines | 50, each at most 8,192 bytes | 431 |
| Trailer with a forbidden field or bad syntax | | 400 |

These are exceptions raised while the handler reads, so the app's [error handler](errors.md) answers
them like an `HttpException`.

Trailers are available from `Request.trailers()` after the body is read.

## Multipart limits

| Limit | Value |
|---|---|
| Parts per body | 1,000 |
| Header lines per part | 20 |
| One part header line | 8,192 bytes |
| One buffered part (text field, or any part under `parts()`) | 10 MiB |

Exceeding one throws `IOException` from the `Multipart` call.

## Responses

| Body | Framing |
|---|---|
| Fixed (`body`) | `Content-Length` |
| `stream(in, length)` | `Content-Length`, bytes relayed |
| `chunked(in)`, `streaming`, SSE, any response with trailers | `Transfer-Encoding: chunked` |
| `204`, `304`, `1xx` | No `Content-Length`, no body |
| Answer to `HEAD` | Headers only. Chunked responses declare `Transfer-Encoding` and send no chunk framing |

The head is written to a per-connection buffer: status line, `Content-Type` (omitted when
`ContentType.NONE`), `Date` (when enabled), your headers, then framing and `Connection`. A fixed body
of up to 8,192 bytes is copied after the head and sent in one write. Larger bodies follow in a
second write.

A response that fails after its head was sent cannot be replaced: Flash closes the connection.

## Protocol switch

A [`DuplexHandler`](handlers.md#duplexhandler) whose `switchesProtocol` is true answers with its
status (WebSocket: `101`), flushes it, and the connection then belongs to that protocol. The
request-loop deadlines no longer apply and the connection does not return to HTTP.
