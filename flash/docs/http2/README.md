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

This page covers the HTTP/2-specific layers only. The transport, message model, and byte
primitives shared with HTTP/1.1 live in [`../core/`](../core/README.md).

## Protocol layers

- [Connection](CONNECTION.md) and [streams](STREAMS.md) — HTTP/2 connection and stream state.
- [Flow control](FLOW-CONTROL.md) — request backpressure and streamed responses.
- [Cleartext](CLEARTEXT.md) — prior knowledge and the 421 misdirected-request rule.
- [WebSockets](WEBSOCKET.md) — RFC 8441 extended CONNECT using the existing WebSocket API.

## Wire internals

- [Serialized writer](WRITER.md) — the single-owner output path and contention model.
- [Frames](FRAMES.md) — frame parsing, validation and error scope.
- [HPACK](HPACK.md) — integer/Huffman coding and static/dynamic table ownership.

## Operate and verify

- [Security](SECURITY.md) — every HTTP/2 limit, default and abuse control.
- [Troubleshooting](TROUBLESHOOTING.md) — GOAWAY/RST_STREAM diagnosis and protocol tracing.
- [Compliance](COMPLIANCE.md) — h2spec, interoperability, fuzzing and deliberate omissions.
- [Performance](PERFORMANCE.md) and [CI baselines](BASELINES.md) — measurements and regression
  gates, including the comparison with nghttpd.
