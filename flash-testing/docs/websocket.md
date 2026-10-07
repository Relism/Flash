# FlashWebSocket

```java
try (FlashWebSocket socket = api.ws("/live")) {
    socket.sendText("hello");
    assertEquals("echo:hello", socket.awaitText(Duration.ofSeconds(2)));
}
```

`api.ws(path)` opens `ws://127.0.0.1:<port><path>` with the JDK's `java.net.http.WebSocket`, so the
handshake, masking and fragmentation are the JDK's. The socket is `AutoCloseable`.

| Method | Behavior |
|---|---|
| `sendText(String)` | Sends one whole text message |
| `awaitText(Duration)` | The next text message. Messages that arrived earlier are queued. `AssertionError` if none arrives in time |
| `awaitClose(Duration)` | Waits for the server to close and returns its close code. `AssertionError` on timeout |
| `close()` | Sends a normal close and waits up to a second for the answer. Never throws |

Only text messages are queued. The client always uses the HTTP/1.1 upgrade.
