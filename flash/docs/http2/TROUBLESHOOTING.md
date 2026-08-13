# HTTP/2 troubleshooting

## Confirm which protocol was selected

For TLS, the client and server must both offer `h2` through ALPN. Enable
`FlashConfiguration.http2Enabled`, use a certificate valid for the requested hostname, then check
with `curl --http2 -v https://host/path` or `nghttp -nv https://host/path`. The trace must report
ALPN `h2`; a successful HTTP/1.1 response usually means HTTP/2 was not enabled or the client did
not offer it.

For plaintext, enable `http2CleartextEnabled` and use prior knowledge:

```bash
curl --http2-prior-knowledge -v http://host:port/path
nghttp -nv http://host:port/path
```

Flash does not support `Upgrade: h2c`. A client configured for Upgrade rather than prior knowledge
will remain on HTTP/1.1.

## Read GOAWAY and RST_STREAM

GOAWAY terminates or drains a connection; `last_stream_id` identifies the highest client stream
the server may have processed. A client may retry a stream above that id only when its own request
semantics make retry safe. RST_STREAM affects one stream and leaves the connection usable.

| Error | What it usually means | What to check |
|---|---|---|
| `NO_ERROR` | Graceful shutdown or connection rotation. | Server lifecycle and configured connection lifetime. |
| `PROTOCOL_ERROR` | Invalid preface, pseudo-header ordering, stream state or frame semantics. | A verbose frame trace and the first rejected stream. |
| `INTERNAL_ERROR` | Handler, response production or I/O failed unexpectedly. | The server exception immediately preceding stream cancellation. |
| `FLOW_CONTROL_ERROR` | A window overflow or DATA exceeded available credit. | Client flow-control implementation and SETTINGS deltas. |
| `SETTINGS_TIMEOUT` | The peer did not complete required SETTINGS progress. | Network stalls or a non-compliant peer. |
| `STREAM_CLOSED` | A frame targeted a stream whose remote side or whole lifecycle was closed. | Late DATA/HEADERS and duplicate terminal frames. |
| `FRAME_SIZE_ERROR` | A frame length violated its type or the negotiated maximum. | The nine-byte frame header and peer frame-size configuration. |
| `REFUSED_STREAM` | Live or pending-output capacity was temporarily exhausted. | Client concurrency versus the advertised maximum; retry only when safe. |
| `CANCEL` | The request, handler or streamed response was cancelled. | Client cancellation and application producer logs. |
| `COMPRESSION_ERROR` | HPACK integer, Huffman, index or table update was invalid. | Header-block bytes and whether an intermediary rewrote them. |
| `CONNECT_ERROR` | A CONNECT tunnel failed. | Upstream tunnel or extended-CONNECT negotiation. |
| `ENHANCE_YOUR_CALM` | A configured abuse, rate, header, body or queue bound was exceeded. | [Security controls](SECURITY.md) and traffic rate before increasing a limit. |
| `INADEQUATE_SECURITY` | TLS does not meet HTTP/2 requirements. | TLS version, cipher suite and ALPN configuration. |
| `HTTP_1_1_REQUIRED` | The peer should retry using HTTP/1.1. | Protocol policy and intermediary compatibility. |

Flash caps GOAWAY debug data, and clients must not depend on it being present. The numeric error
code and last stream id are the reliable diagnostic fields.

## Capture a frame trace

Flash does not log every frame in production: frame logs leak header and traffic metadata and add
work to the hottest connection loop. Reproduce against a verbose client instead:

```bash
nghttp -nv https://host/path
curl --http2 -v https://host/path
```

`nghttp -nv` prints SETTINGS, HEADERS, DATA, WINDOW_UPDATE, RST_STREAM and GOAWAY in wire order. For
a server-side-only failure, capture the connection with an approved packet tool; TLS traffic must
be decrypted in a controlled environment. Never attach production header blocks or payloads to a
ticket without redacting credentials and personal data.

## Common misconfiguration patterns

1. **HTTP/2 switch disabled.** `http2Enabled` controls TLS ALPN and
   `http2CleartextEnabled` controls prior knowledge independently.
2. **Wrong cleartext mode.** The client sends `Upgrade: h2c`; Flash expects the RFC 9113 prior-
   knowledge preface on the shared plaintext listener.
3. **ALPN or certificate mismatch.** A custom `TlsConfig.ofContext` omits `h2`, or hostname
   verification rejects the certificate before HTTP/2 starts. Inspect the TLS handshake first.

If a connection closes under load rather than at startup, compare the observed rate and retained
stream count with [the security defaults](SECURITY.md), especially reset/stream creation budgets,
the 64 concurrent-stream setting, header assembly time and stream idle time.
