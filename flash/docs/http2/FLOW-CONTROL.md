# HTTP/2 bodies and flow control

HTTP/2 applies flow control independently to the connection and to every stream. Flash advertises
a 1 MiB receive window at both levels and sends WINDOW_UPDATE only after the application has
consumed at least half a window. A DATA frame decrements both windows by its complete payload
length, including the pad-length byte and padding; only its unpadded data reaches the handler.

## Request bodies

Known bodies up to 64 KiB remain in one reusable contiguous stream buffer. Their handler is
dispatched at END_STREAM, and `RequestBody.bytes()` performs the only allocation: the byte array
returned to application code. For a 1,024-byte body JMH reports exactly 1,040 B/op, the array plus
its object header, with no framework allocation around it.

Larger or unknown-length bodies dispatch after request headers. DATA is copied out of the frame
reader into a connection-owned pool of 64 reusable 16 KiB buffers. Small adjacent frames coalesce
inside a buffer, so the pool is bounded by bytes rather than frame count. The existing
`RequestBody.stream()` blocks only the handler's virtual thread when data is absent. Buffers return
to the pool as reads consume them, and that consumption reopens both receive windows. If a handler
does not read its body, the normal post-handler drain performs the same bounded consumption.

The connection window and pool both cover exactly 1 MiB, so the peer can never hold more credit
than the server can store before backpressure takes effect. Per-stream accepted body bytes remain
bounded by `ServerConfig.maxBodySize`. Declared content length is parsed without a String and checked
against the unpadded DATA total at END_STREAM.

## Responses

Fixed byte arrays, known-length streams and unknown-length streams all use one resumable
`Http2ResponseWriter`. It emits DATA frames no larger than the peer's frame limit, the available
connection window, the available stream window and the reusable 16 KiB relay buffer. A
WINDOW_UPDATE schedules the stream on the shared virtual-thread executor; application streams are
never read by the demultiplexer.

`Response.chunked(InputStream)` means unknown-length streaming at the application API. HTTP/2 has
no chunked transfer coding, so Flash emits ordinary DATA followed by END_STREAM and never sends a
`transfer-encoding` field. `Response.stream(InputStream, length)` emits `content-length` and fails
the stream if the source ends before that length.

## Verification

- A real Java HTTP/2 client uploads and downloads 100 MiB over TLS; both directions are validated
  byte-for-byte without materializing the test payload.
- A synthetic 100 MiB response proves serialized scratch storage stays below 64 KiB.
- h2spec sections 5, 6.1, 6.9 and 8: 50 passed, one h2spec-skipped case, zero failures.
- Clean Maven build with JMH sources: 633 tests, no failures.
- JMH request streaming: 159.408 ns/op, 0.001 B/op, no GC.
- JMH response streaming frame: 219.090 ns/op, 0.002 B/op, no GC.
