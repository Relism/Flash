# HTTP/2 cleartext and proxying

TLS HTTP/2 and cleartext HTTP/2 have independent rollout controls:

- `http2Enabled` advertises `h2` through TLS ALPN.
- `http2CleartextEnabled` accepts the HTTP/2 prior-knowledge preface on plaintext listeners.

Both default to `false`. Cleartext support follows RFC 9113 prior knowledge. The obsolete
HTTP/1.1 `Upgrade: h2c` transition is intentionally unsupported.

## Upstream client

`Http2Client` is a synchronous, pooled client for reverse-proxy handlers. It supports TLS ALPN and
h2c prior knowledge, request and response bodies, flow control, response status, trailers,
SETTINGS, PING, GOAWAY and RST_STREAM. Connections are pooled by origin and reused across
sequential exchanges. A connection serializes its exchanges deliberately; this keeps ownership
and HPACK state explicit and bounded while virtual threads allow independent origins to progress.
It is not intended to replace a general-purpose HTTP client.

`HttpProxy.toHttp2(origin, client)` adapts Flash's shared `Request` and `Response` models to that
client. It preserves the incoming raw path and query, body, end-to-end fields and trailers.

## Header conversion

`HopByHopHeaders` is the single policy used at connection boundaries. It removes fields named by
`Connection`, the standard hop-by-hop set, HTTP/2-forbidden fields and pseudo-fields. `TE` is
forwarded only as `trailers` when the target is HTTP/2. Tests execute the same policy for all four
HTTP/1.1 and HTTP/2 source/target combinations.

## Authority and 421

On TLS HTTP/2 connections, Flash checks `:authority` against the selected certificate's DNS/IP
subject alternative names. An authority outside that served set receives `421 Misdirected
Request`, allowing a coalescing client to retry on a different connection. Exact names and
single-label wildcards are supported; h2c has no certificate identity and is unaffected.

## Trailer guarantee

The proxy copies request trailers only after the incoming body reaches EOF and emits upstream
trailers as a trailing HEADERS block. Response trailers follow the reverse path and remain
trailers on both HTTP/2 and HTTP/1.1 chunked downstream connections. The live relay tests cover
both downstream protocols.
