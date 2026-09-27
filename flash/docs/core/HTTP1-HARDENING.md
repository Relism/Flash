# HTTP/1.1 hardening

Audience: operators. This is the document to read when a `400`/`413`/`414`/`431`/`501` shows up
in the logs and it isn't obvious why. Every rejection rule Flash's HTTP/1.1 parser enforces is
listed here with its RFC citation and the status it produces. Contributor-level detail (why each
check is implemented the way it is, the exact code paths) lives in the Javadoc of
`RequestParser`, `ChunkedInputStream`, and `dev.relism.flash.http.MalformedRequestException`.

Every rejection in this document has one thing in common: **the connection is always closed
afterwards, never kept alive.** A rejected request is exactly the situation a smuggling attack
needs a reusable connection for, so none of these rejections offer one — see
`MalformedRequestException`'s Javadoc.

## Request-smuggling defenses (RFC 9112 §6.1)

| Rule | Status | Detail |
|---|---|---|
| `Content-Length` and `Transfer-Encoding` both present | `400` | The canonical CL.TE/TE.CL smuggling vector. Rejected regardless of which header appears first. |
| Multiple `Content-Length` lines with **differing** values | `400` | Identical repeated values are tolerated (RFC 9110 §8.6 permits treating them as one). |
| `Transfer-Encoding` whose **final** coding is not `chunked` | `501` | Flash implements only `chunked`; anything else (`gzip` alone, or `chunked, gzip` — chunked must be *last*) is unsupported. |

## Strict `Content-Length` parsing (RFC 9110 §8.6)

| Input | Status |
|---|---|
| Empty value | `400` |
| Any non-digit byte (including a leading `+` or `-`) | `400` |
| More than 19 digits | `400` |
| Value overflows `Long.MAX_VALUE` | `400` |
| Value exceeds `ServerConfig.maxBodySize` (10 MiB by default) | `413` |

The previous parser silently skipped non-digit characters (`"5abc"` parsed as `5`; `"-1"` parsed
as `1`) instead of rejecting them — this is the fix.

## Header and request-line limits (`Http1Limits`)

| Limit | Default | Status when exceeded |
|---|---|---|
| `MAX_HEADER_COUNT` | 100 | `431 Request Header Fields Too Large` |
| `MAX_HEADER_NAME_LENGTH` | 256 B | `431` |
| `MAX_HEADER_VALUE_LENGTH` | 8192 B | `431` |
| `MAX_REQUEST_LINE_LENGTH` | 8192 B | `431` |
| Header block exceeds `maxHeaderBufferSize` (or the connection ends before it completes) | configurable, default 64 KiB | `431` |

## Line-terminator and header-syntax correctness (RFC 9112 §5)

| Rule | Status |
|---|---|
| A `\r` not immediately followed by `\n` (bare CR) | `400` — a known desynchronization/smuggling surface |
| A header line beginning with whitespace (obsolete line folding, RFC 9112 §5.2) | `400` |
| A header name containing a byte outside RFC 9110 §5.6.2's `tchar` set | `400` |
| A header line with no `:` | `400` |

## Chunked transfer safety (RFC 9112 §7.1, `Http1Limits`)

| Limit | Default | Status when exceeded |
|---|---|---|
| `MAX_CHUNK_SIZE` | 16 MiB | `413` |
| Chunk-size line longer than 16 hex digits | — | `400` |
| `MAX_CHUNK_EXT_LENGTH` (the optional `;name=value` after a chunk size) | 256 B | `400` |
| `MAX_CHUNKS_PER_BODY` | 100 000 | `413` |
| `MAX_TRAILER_COUNT` | 50 | `431` |
| A chunk's data not followed by `\r\n`, or a malformed chunk-size/trailer terminator | — | `400` |

Trailers are consumed within the bounds above and exposed through `Request.trailers()` on both
HTTP/1.1 and HTTP/2.

## Timeouts (`ServerConfig`)

| Setting | Default | Covers |
|---|---|---|
| `idleKeepAliveTimeoutMs` | 60 000 | How long a keep-alive connection may sit idle waiting for its next request. |
| `headerReadTimeoutMs` | 10 000 | Once the first byte of a request arrives, how long the full header block may take. |
| `bodyReadTimeoutMs` | 30 000 | How long reading the body (by the handler, or the automatic post-response drain) may take. |
| `shutdownDrainTimeoutMs` | 15 000 | How long graceful shutdown waits for in-flight requests before force-closing. |

These are enforced by an **absolute deadline**, not merely `Socket.setSoTimeout`. A per-read
socket timeout alone never trips against a peer that sends one byte just often enough to keep
each individual read alive (the classic slowloris shape) — see
`dev.relism.flash.transport.BufferedByteSource`'s Javadoc for how the absolute deadline is
implemented on top of the JDK's per-read-only timeout API.

## TLS (RFC 9113 §9.2.2, applies once a listener offers `h2` over ALPN)

- The TLS handshake is forced explicitly (not left to the JDK's lazy on-first-read trigger)
  before any protocol decision is made, and is bounded by `headerReadTimeoutMs`.
- When a listener's `TlsConfig.applicationProtocols` includes `"h2"`, the enabled TLS 1.2 cipher
  suite list is filtered against the RFC 9113 Appendix A blocklist
  (`TlsConfig.TLS12_H2_BLOCKED_CIPHERS`, ~280 entries). TLS 1.3 is never affected — none of its
  cipher suites are on that list.
- `ServerConfig.http2Enabled` advertises `h2` on TLS listeners. The independent
  `http2CleartextEnabled` switch accepts the h2c prior-knowledge preface on plaintext listeners.
