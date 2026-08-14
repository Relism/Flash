# HTTP/2 cleartext

TLS HTTP/2 and cleartext HTTP/2 have independent rollout controls:

- `http2Enabled` advertises `h2` through TLS ALPN.
- `http2CleartextEnabled` accepts the HTTP/2 prior-knowledge preface on plaintext listeners.

Both default to `false`. Cleartext support follows RFC 9113 prior knowledge. The obsolete
HTTP/1.1 `Upgrade: h2c` transition is intentionally unsupported.

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

An outbound HTTP/2 client and reverse-proxy adapter (`Http2Client`, `HttpProxy`) were built
against this cleartext support but had no caller anywhere in `flash` core — an HTTP/1.1+2 server
framework has no business shipping an outbound client. That code has been removed; if a
reverse-proxy capability is needed later, it belongs in its own `flash-extensions/flash-ext-*`
module, not in core.
