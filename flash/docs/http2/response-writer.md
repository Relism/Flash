# Response writer and frame writer

All HTTP/2 output of a connection reaches the socket through one `Http2FrameWriter`. This is the only
place that writes, because every stream of a connection shares one socket.

## Response writer

Each stream has an `Http2ResponseWriter` that serializes a response into its own reusable buffer,
outside any lock:

1. `HEADERS` (and `CONTINUATION` if the block exceeds the peer's frame size), built from `:status`,
   `content-type`, `date`, `content-length`, the response's fields and nothing connection-specific.
2. `DATA` frames, each at most the peer's `MAX_FRAME_SIZE` and at most the available flow-control
   window.
3. Trailers as a final `HEADERS` frame with `END_STREAM`. Without trailers `END_STREAM` rides on the
   last frame.

`content-length` is sent for fixed and known-length bodies and is omitted for `204`, `304` and `1xx`.
For `HEAD`, no `DATA` is sent. A small response (headers and a body that fit the frame size and the
window) is built as one unit and written in one call.

A fixed response that does not fit a frame or the window is sent as several batches. A batch is built,
written, and only then does the next one start, which keeps one stream's data from monopolizing the
connection.

## Frame writer

`Http2FrameWriter` serializes access to the socket. A caller hands it a `WriteIntent`, an object that
already holds finished frame bytes (`buffer()`, `offset()`, `length()`). The writer never builds
frames. It issues one bulk `sink.write` per intent while holding a lock, so the lock covers the
system call and never serialization work. The bytes of one intent are never interleaved with another's.

The lock is a `ReentrantLock`, not `synchronized`, so a virtual thread blocked on the socket
unmounts instead of pinning its carrier.

### Algorithm

There are two lock-free multi-producer queues: the priority queue for control frames and the ordinary
queue for stream data. An intent is its own queue node, so queuing allocates nothing.

```
write(intent):                            // stream data
    if both queues are empty and tryLock():
        drive(intent)
    else:
        queue.offer(intent)
        if tryLock(): drive(null)

writePriority(intent):                    // SETTINGS ACK, PING ACK, RST_STREAM, GOAWAY, WINDOW_UPDATE
    priorityQueue.offer(intent)
    if tryLock(): drive(null)

drive(first):
    write first if present, then drain: priority queue first, then one ordinary intent, repeat
    unlock()
    while a queue has work:               // re-check after unlock
        if !tryLock(): break              // another thread owns draining now
        drain(); unlock()
```

Two rules make this correct:

- **No lost wakeup.** A producer can queue an intent just as the lock holder finds the queue empty
  and is about to unlock. The holder re-checks the queues after unlocking and tries the lock again,
  and the producer tries the lock itself after queuing. Between the two, an intent is never left in
  a queue with nobody responsible for writing it.
- **Order per producer.** The fast path, writing without queuing, is taken only when both queues are
  observed empty. Otherwise a producer whose earlier frames are still queued could write a later one
  ahead of them.

`drain()` is called by the connection loop after each frame it processes, and flushes anything
queued.

### Write timeout

A write blocks when the peer stops reading, and the lock holder then holds up every stream on the
connection. A shared daemon thread (`flash-http2-write-timeout-reaper`) scans all writers every
50 ms. A writer observed blocked in `sink.write` for at least `WRITE_TIMEOUT_MS / 50` consecutive
scans (30 s gives 600) has its thread interrupted, and the write fails with an
`InterruptedIOException` (`HTTP/2 write timed out`). Precision is therefore one scan interval.
`Socket.setSoTimeout` cannot be used because it bounds reads only.

The queues of acknowledgements are bounded: at most 64 pending `SETTINGS` ACKs and 64 pending `PING`
responses.
