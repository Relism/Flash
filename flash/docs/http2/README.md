# HTTP/2 in Flash

Flash treats HTTP/1.1 and HTTP/2 as peer transports behind one connection boundary. TLS ALPN or
the cleartext prior-knowledge preface selects a protocol once; both paths then feed the same
router, `Request`, `Response`, handler, trailer, streaming and WebSocket APIs. HTTP/2 adds a
bounded frame decoder, HPACK codec, stream state machine, two-level flow control and one serialized
writer per connection. Application code does not branch on the wire protocol.

The implementation is deliberately layered:

```text
listener / TLS
    -> protocol negotiation
        -> HTTP/1.1 parser ---------+
        -> HTTP/2 frames + HPACK ----+-> shared request model -> router -> handler
                                         shared response model
        <- HTTP/1.1 serializer -----+
        <- HTTP/2 stream writer ----+
```

## Start here

- [HTTP/1.1 hardening](HTTP1-HARDENING.md) — message-boundary rules, timeouts and negotiation.
- [Transport](TRANSPORT.md) — listeners, connection ownership, TLS and virtual threads.
- [Message model](MESSAGE-MODEL.md) — shared request/response objects and their lifetime contract.
- [Connection](CONNECTION.md) and [streams](STREAMS.md) — HTTP/2 connection and stream state.
- [Flow control](FLOW-CONTROL.md) — request backpressure and streamed responses.
- [Trailers and streaming](TRAILERS-AND-STREAMING.md) — the public cross-protocol APIs.
- [Cleartext and proxying](CLEARTEXT-AND-PROXY.md) — prior knowledge and the upstream h2 client.
- [WebSockets](WEBSOCKET.md) — RFC 8441 extended CONNECT using the existing WebSocket API.

## Wire internals

- [Byte primitives](BYTES.md) — reusable views, scanning and bounded slice lifetimes.
- [Serialized writer](WRITER.md) — the single-owner output path and contention model.
- [Frames](FRAMES.md) — frame parsing, validation and error scope.
- [HPACK](HPACK.md) — integer/Huffman coding and static/dynamic table ownership.

## Operate and verify

- [Security](SECURITY.md) — every HTTP/2 limit, default and abuse control.
- [Troubleshooting](TROUBLESHOOTING.md) — GOAWAY/RST_STREAM diagnosis and protocol tracing.
- [Compliance](COMPLIANCE.md) — h2spec, interoperability, fuzzing and deliberate omissions.
- [Performance](PERFORMANCE.md) and [CI baselines](BASELINES.md) — measurements and regression
  gates, including the comparison with nghttpd.

## Design history

[Decisions](DECISIONS.md) records non-obvious trade-offs and rejected alternatives. The
implementation plan is retained as historical engineering evidence; it is not required to use or
extend the runtime.
