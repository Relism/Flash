# Server-Sent Events

SSE is output-only. An `SseHandler` is a `DuplexHandler` that writes `text/event-stream`.

```java
@Sse("/clock")
final class Clock extends SseHandler {
    @Override protected void handle(Request request, SseStream stream) throws Exception {
        while (stream.isOpen()) {
            stream.send(SseEvent.of(Instant.now().toString()).event("tick"));
            Thread.sleep(1_000);
        }
    }
}
```

`@Sse(path)` declares a `GET` route. Without scanning, register an instance:
`app.get("/clock", new Clock())`. Middleware runs before the stream starts, so a request can be
refused with an `HttpException`.

## Response

Flash sets `Content-Type: text/event-stream` and `Cache-Control: no-cache`, sends the headers when the
handler is entered, and ends the response when `handle` returns. HTTP/1.1 uses chunked encoding;
HTTP/2 uses DATA frames.

## SseStream

| Method | Sends |
|---|---|
| `send(String)` | An event with that data |
| `send(SseEvent)` | An event with optional `event`, `id` and `retry` |
| `send(bytes, off, len)` | An event whose data is those bytes |
| `await()` | Blocks until the stream is closed, sending a `:` comment line every 15 seconds |
| `close()`, `isOpen()` | End the stream, and tell whether it is still open |

Sending and closing are safe from any thread, so an event source can feed the stream from another
thread while the handler waits in `await()`:

```java
@Override protected void handle(Request request, SseStream stream) throws Exception {
    try (var subscription = bus.subscribe(message -> stream.send(SseEvent.of(message)))) {
        stream.await();
    }
}
```

`await()` is also how a disconnected client is noticed: the keep-alive write fails with an
`IOException` once the peer is gone. Each `send` flushes. A `send` on a closed stream throws
`IOException`.

## SseEvent

`SseEvent.of(data)` creates an event. `event(name)`, `id(id)` and `retry(Duration)` return copies
with that field set. `event` and `id` must fit on one line, otherwise `IllegalArgumentException`.
Data that contains line breaks is written as one `data:` line per line. Fields are written in the
order `id`, `event`, `retry`, `data`.
