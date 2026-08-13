# HTTP/2 streams and request dispatch

Each connection owns a fixed-capacity `Http2StreamTable`. Client stream identifiers are validated
as odd, non-zero and strictly increasing before a stream object is acquired. The table uses
primitive open addressing and a bounded object free list; it never grows beyond the advertised 64
concurrent streams. An excess request receives `REFUSED_STREAM`, allowing the peer to retry it.

## State model

`Http2StreamState` represents `IDLE`, `OPEN`, `HALF_CLOSED_REMOTE`, `HALF_CLOSED_LOCAL` and
`CLOSED`. A class-initialized table maps every receive/send event to either its next state or the
correct stream error. Frames racing with a recently closed stream follow RFC 9113 §5.1 rather than
being rejected uniformly.

## Header and request model

Decoded HPACK fields are copied into storage owned by the stream. Before dispatch,
`PseudoHeaders` enforces ordering, uniqueness, required request pseudo-fields, lowercase regular
names, connection-specific-field rejection, the `te: trailers` exception and host/authority
consistency. Pseudo-fields are not exposed as regular headers; `:authority` is also visible as
`host` so existing middleware sees the same authority through HTTP/1.1 and HTTP/2.

The stream assembles the existing protocol-neutral `Request`, `RequestLine`, `RequestBody` and
`HeaderView` models. Path/query splitting, routing, middleware, not-found handling and exception
handling therefore use the same code as HTTP/1.1. `FastPathRouterImpl` is unchanged.

## Dispatch and ownership

The connection thread decodes and validates frames only. Completed bodyless streams are queued in
a fixed array while more frame bytes are already buffered, then submitted to the server's shared
virtual-thread executor before the demultiplexer waits for the network again. This preserves burst
admission semantics without adding a dispatch timer or blocking the connection thread.

The stream owns its pooled request, response, body, decoded-header arena and response writer.
Normal response completion releases it through the serialized writer callback. RST_STREAM marks a
queued or running stream cancelled and defers release to that sole owner; setup, routing and handler
failures send an appropriate stream reset and release in the failure path. A 100,000-cycle test
proves stable pool counts, and an immediate request/reset/request regression test covers reuse
while dispatch is pending.

## Verification

- Phase 11 clean Maven build with JMH sources: 633 tests, no failures.
- h2spec sections 5 and 8: 39/39 after request DATA byte accounting landed.
- Java `HttpClient` negotiates HTTP/2 over TLS and runs an existing parameterized route unchanged.
- curl prior-knowledge h2c receives a valid `200` response and body.
- Current JMH pooled lifecycle (HPACK decode, request assembly, response write and release):
  483.571 ns/op, 0.003 B/op, no GC.
