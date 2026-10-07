# WebSocket

WebSocket (RFC 6455) is a `DuplexHandler`. One handler class serves clients on HTTP/1.1 and on
HTTP/2 (RFC 8441).

```java
@Ws("/echo")
final class Echo extends WebSocketHandler {
    @Override protected void onMessage(WebSocketSession session, WebSocketFrame message) throws Exception {
        session.echo(message);
    }
}

app.scan("com.acme.ws");                 // or: app.ws("/echo", new Echo());
```

A WebSocket route has its own key, so `@GET("/chat")` and `@Ws("/chat")` can share a path.
Middleware attached to the route runs before the handshake, so it can reject a connection with an
`HttpException`.

## Handler callbacks

| Callback | When |
|---|---|
| `onWebSocketInit()` | Once at boot, with `services()` available |
| `onOpen(session)` | After the handshake |
| `onMessage(session, frame)` | For every complete message. Required |
| `onClose(session, code, reason)` | When the connection ends normally |
| `onError(session, failure)` | When the handler or the protocol failed |

All callbacks of one connection run on that connection's virtual thread, one after another. A
message is not delivered until `onMessage` for the previous one returned, which gives backpressure.
Blocking in a callback stops reading from that client.

## Messages

A message is delivered whole. Flash unmasks it, reassembles fragments, answers pings, and runs the
closing handshake, so the handler sees only messages.

`WebSocketFrame` is a view valid only during `onMessage`:

| Method | Returns |
|---|---|
| `isText()`, `isBinary()` | The message type |
| `text()` | The message decoded as a UTF-8 `String` |
| `copy()` | A new `byte[]` with the payload |
| `array()`, `offset()`, `length()` | The payload in place, without a copy |

A message that arrives in one frame and fits in the connection's read buffer is unmasked where it
lies. A message split across frames or buffers is gathered once into a buffer. Its size is limited by
[`wsFrameBufferSize`](config.md#limits) (default 64 KiB). Text is validated as UTF-8 as it arrives.

## Sending

`WebSocketSession` is safe to use from any thread. Sends are serialized with a lock.

| Method | Sends |
|---|---|
| `sendText(String)`, `sendText(bytes, off, len)` | A text message |
| `send(bytes, off, len)` | A binary message |
| `echo(frame)` | The same message, same type |
| `close(code)`, `close(code, reason)` | A close frame. The reason is at most 123 UTF-8 bytes |
| `isOpen()` | False once a close frame was sent |
| `await()` | Blocks until the closing handshake completes |
| `request()` | The handshake `Request`, for headers, cookies and path parameters |

Sending after the connection closed throws `IOException`. `close` accepts 1000 to 1014 except 1004,
1005 and 1006, and 3000 to 4999; other codes throw `IllegalArgumentException`.

## Closing

| Cause | Close code |
|---|---|
| Handler returns normally, peer closes | 1000, or the peer's own valid code |
| Frame violates RFC 6455 (bad opcode, unmasked client frame, RSV bits set, bad control frame) | 1002 |
| Text that is not UTF-8 | 1007 |
| Message larger than `wsFrameBufferSize` | 1009 |
| Callback threw an `HttpException` with status 400 or 422 | 1007 |
| Callback threw anything else | 1011, and the failure is logged unless it was an I/O error |

## Handshake

| Protocol | Request | Response |
|---|---|---|
| HTTP/1.1 | `GET` with `Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Key` | `101 Switching Protocols` with `Sec-WebSocket-Accept` |
| HTTP/2 | extended CONNECT: `:method CONNECT`, `:protocol websocket` | `200` |

HTTP/2 clients may use this only because Flash advertises `SETTINGS_ENABLE_CONNECT_PROTOCOL = 1`. The
request is routed as a `GET`. A refused CONNECT is answered by status alone, with no body. On
HTTP/1.1, `Upgrade: websocket` without `Connection: Upgrade` answers `426`, and a missing
`Sec-WebSocket-Key` answers `400`. A plain `GET` to a path that has only a WebSocket route is a
`404`.

Flash does not negotiate subprotocols or extensions: `permessage-deflate` is not available, and a
frame with an RSV bit set closes the connection with 1002.
