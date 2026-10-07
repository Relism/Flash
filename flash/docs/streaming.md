# Streaming and Duplex

A response normally has a body Flash knows when the handler returns. Three mechanisms cover the rest.

| Need | Use |
|---|---|
| Send a file or any `InputStream` of known size | `res.stream(in, length)` |
| Send an `InputStream` of unknown size | `res.chunked(in)` |
| Produce the body yourself, with trailers, after middleware ran | `res.streaming(out -> ...)` |
| Own the whole exchange: read the request while writing | a `DuplexHandler` |

`stream` and `chunked` take an `InputStream` that Flash reads and closes, including when the client
disconnects mid-transfer. `stream` fails the response if the source ends before `length` bytes.

## Response.streaming

```java
app.get("/export", (req, res) -> res
        .type(ContentType.NDJSON)
        .trailer("X-Rows", "0")
        .streaming(out -> {
            for (Row row : repository.scan()) {
                out.write(row.json());
                out.write('\n');
            }
        }));
```

The callback runs after the handler and every middleware have returned, so all of them can still set
status and headers. These leave with the first `write` or `flush`; trailers leave when the callback
returns. Consequences:

- Headers cannot change after the first write: `status`, `header` and `type` throw
  `IllegalStateException`.
- A callback that never writes answers like a handler with no body.
- A callback that throws before writing is answered by the [error handler](errors.md). After the
  first write the exchange is broken off: the connection closes on HTTP/1.1 and the stream is reset
  on HTTP/2.
- Writes block while the peer cannot take more data. On HTTP/2 this follows the peer's flow-control
  window; see [flow-control](http2/flow-control.md).
- For `HEAD` requests nothing is written.

The `OutputStream` is a `Duplex`. HTTP/1.1 sends the body with `Transfer-Encoding: chunked` and ends
it with the final chunk, trailers included. HTTP/2 sends DATA frames.

## Duplex

`Duplex` is the byte channel behind every streaming exchange. It is an `OutputStream` for the
response and also gives in-place access to the request bytes.

| Method | Meaning |
|---|---|
| `write(b, off, len)`, `flush()` | Send response bytes. The first call also sends status and headers |
| `fill()` | Blocks until request bytes are available and returns how many. `-1` once the peer is done sending. Bytes not yet advanced over are returned again without waiting |
| `array()`, `offset()` | Where those bytes are. Valid until the next `fill` or `advance` |
| `advance(n)` | Consumes `n` of them |

One thread may read and one may write at the same time. The array belongs to the connection: copy
what you keep.

A `DuplexHandler` receives the `Duplex` in `handle(request, response, duplex)`. See
[handlers.md](handlers.md#duplexhandler). On HTTP/1.1, a handler whose `switchesProtocol` is true
takes over the socket after a `101` (this is how WebSocket works); otherwise the response is chunked
and the request body is read through `fill`. On HTTP/2 the duplex is one stream.

Core ships two duplex protocols: [WebSocket](websocket.md) and [SSE](sse.md). Framing,
serialization and message cardinality belong to the protocol, not to `Duplex`.
