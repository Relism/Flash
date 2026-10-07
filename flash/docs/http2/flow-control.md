# Flow control

HTTP/2 limits data per stream and per connection. Flash applies both on each direction.

## Receiving

Flash advertises a 1 MiB receive window for each stream and for the connection. A `DATA` frame
reduces both windows by its full payload, padding included; only the unpadded bytes reach the
handler. Data beyond a window is `FLOW_CONTROL_ERROR`.

`WINDOW_UPDATE` is sent only after the application consumed data: once at least half a window (512
KiB) has been consumed, a single update returns it. This applies to the connection and, separately,
to each stream. A handler that does not read its body does not stall the connection: the discard
after the handler returns consumes it the same way.

### Request bodies

| Body | Storage |
|---|---|
| Known length up to 64 KiB (`INLINE_BODY_THRESHOLD`) | One reusable contiguous buffer. The handler is dispatched at `END_STREAM`. `bytes()` allocates only the returned array |
| Larger, or unknown length | Copied from the frame into a pool of 64 buffers of 16 KiB per connection. The handler is dispatched after the headers and blocks, on its virtual thread only, when no data has arrived |

If the pool is exhausted the stream is reset with `ENHANCE_YOUR_CALM`. The pool covers exactly the
1 MiB connection window, so a peer cannot hold more credit than the server can buffer. Buffers
return to the pool as the handler reads, and consumption reopens the windows.

Body checks, each a stream error `PROTOCOL_ERROR`: total bytes above
[`maxBodySize`](../config.md#limits), more bytes than `content-length` declared, a conflicting or
malformed `content-length`. A stream that sends more than 1,000 consecutive empty `DATA` frames is reset
with `ENHANCE_YOUR_CALM`.

## Sending

The send windows start at 65,535 and follow the peer's `SETTINGS_INITIAL_WINDOW_SIZE` and
`WINDOW_UPDATE` frames. Before writing, a stream reserves `min(data wanted, connection window,
stream window)`. Unused credit is refunded.

A response writes DATA frames no larger than the peer's `MAX_FRAME_SIZE`. When a window is empty:

- a fixed or streamed response is parked, and a `WINDOW_UPDATE` schedules it again on the
  executor;
- a `Response.streaming` callback or a duplex write blocks its virtual thread until the window
  opens.

The demultiplexer never waits for application data or for a window.

`Response.chunked(InputStream)` is written as ordinary DATA ending in `END_STREAM`: HTTP/2 has no
chunked transfer coding and Flash never sends `transfer-encoding`. `Response.stream(in, length)` sends
`content-length` and fails the stream if the source ends early.
