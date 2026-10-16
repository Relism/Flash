# Streams

## Stream table

Each connection owns a fixed-capacity table of 64 streams (the advertised
`MAX_CONCURRENT_STREAMS`), using open addressing on primitive stream ids and a bounded free list of
reusable stream objects. It never grows. A new request stream beyond the capacity is answered with
`RST_STREAM(REFUSED_STREAM)`, which tells the client it may retry.

Client stream ids must be odd, non-zero and increasing. A frame on a stream that was closed or reset
follows RFC 9113 section 5.1: a stream error `STREAM_CLOSED` after a reset, a connection error
`STREAM_CLOSED` after a normal close, `PROTOCOL_ERROR` for an id that was never opened.

## State machine

Stream states are `IDLE`, `OPEN`, `HALF_CLOSED_REMOTE`, `HALF_CLOSED_LOCAL` and `CLOSED`. A table
maps each received or sent event (`HEADERS`, `DATA`, `END_STREAM`, `RST_STREAM`) to the next state or
to the error the RFC requires.

## Request headers

After HPACK decoding, `PseudoHeaders` enforces:

- Pseudo-headers come first, are known, and appear once.
- A normal request needs `:method`, `:scheme`, `:path` and `:authority`, and `:path` is not empty.
- `CONNECT` needs `:authority` and forbids `:scheme` and `:path`.
- Extended CONNECT (`:protocol` present) needs `:method CONNECT` and all four of the others.
- `:method` is a token (RFC 9110); otherwise the stream is reset with `PROTOCOL_ERROR`. A token
  outside `HttpMethod` is accepted and routed as [routing.md](../routing.md#methods) describes.
- Regular field names are lowercase and connection-specific fields are rejected.
- `host`, when present, must equal `:authority`.

Trailing fields may not contain pseudo-headers, `content-length`, `host` or `te`, and a trailing
`HEADERS` frame must carry `END_STREAM`.

Pseudo-headers are not exposed as headers. `:authority` is also answered for `host`, so
`req.header("Host")` works the same on both protocols. The stream builds the same `Request`,
`RequestLine` and `RequestBody` objects HTTP/1.1 uses, and calls `Router.dispatch`, so routing,
middleware and error handling are shared.

## Dispatch

The demultiplexer decodes frames and queues completed requests. When no more input is already
buffered, the queued streams are submitted to the server's virtual-thread executor. A request with no
body or a body up to 64 KiB is dispatched when the stream ends; a larger or unknown-length body is
dispatched as soon as its headers are complete, and the handler reads the body as DATA arrives.

A stream owns its request, response, decoded headers, body buffers and response writer, and is
returned to the pool when the response completes or the stream is reset. A `RST_STREAM` from the
peer marks a running stream cancelled before it closes it, so a write the handler was making fails
as an `IOException` ("stream reset"), and so does a read of a request body that had not reached
`END_STREAM`, instead of ending it early; the thread that owns the stream releases it. A release names
the stream id it owns: a thread that holds a pooled stream past its release cannot recycle the
request the pool handed that stream to next. A stream's state is changed under its write lock, by
the demultiplexer and the writing thread alike.

A stream with no frame activity in either direction for `h2StreamIdleTimeoutMs` (60 s) is reset with
`CANCEL`.

## Misdirected requests

On a TLS connection, a request whose `:authority` is not covered by the connection's certificate is
answered `421 Misdirected Request`, so a client that reused the connection for another origin
retries on a new one. See [tls.md](../tls.md#http2-and-certificates).

## WebSocket over extended CONNECT

Flash advertises `SETTINGS_ENABLE_CONNECT_PROTOCOL = 1`. A request with `:method CONNECT` and
`:protocol websocket` reaches its route as a `GET` and is answered `200` instead of `101`; a refusal
is the status alone. After that, the stream carries WebSocket frames inside DATA frames, under flow
control. See [websocket.md](../websocket.md#handshake).
