# WebSockets over HTTP/2

Flash implements RFC 8441 extended CONNECT alongside the existing HTTP/1.1 WebSocket upgrade.
Both transports resolve the same `ws(path, handler)` registration through `AbstractWsRouter` and
run the same `WebSocketSession`, frame parser, handler callbacks, and close lifecycle.

## Protocol negotiation

Every HTTP/2 server connection advertises `SETTINGS_ENABLE_CONNECT_PROTOCOL` (`0x8`) with value
`1`. A WebSocket request uses this pseudo-header shape:

```text
:method    CONNECT
:protocol  websocket
:scheme    https       # or http
:authority example.com
:path      /live
```

The normal HTTP/1.1 upgrade fields (`Connection`, `Upgrade`, `Sec-WebSocket-Key`, and
`Sec-WebSocket-Accept`) are neither required nor permitted on this path. A matched route receives
status `200`; a missing route receives `404`.

## Shared application behavior

At the router boundary, an extended CONNECT for `websocket` is represented as a GET so the
existing WebSocket router can be reused without a second registration table or protocol-specific
handler API. The wire validator retains the original CONNECT semantics and rejects malformed
pseudo-header combinations before dispatch.

Request DATA is exposed through the existing streaming `RequestBody`. WebSocket output passes
through the common push-style `ResponseStream`, so HTTP/2 stream and connection flow-control
windows apply without changing the WebSocket codec. Messages may cross any number of DATA-frame
boundaries; those boundaries are invisible to RFC 6455 framing. Client-to-server masking remains
mandatory and is validated by the same frame parser used for HTTP/1.1.

## Lifecycle and backpressure

Response HEADERS are sent before the push producer is allowed to wait for request DATA. This is
required for a full-duplex protocol: waiting for the first WebSocket frame before publishing the
successful CONNECT response would deadlock compliant clients. Subsequent response batches block
behind the bounded response bridge and resume when HTTP/2 flow-control credit becomes available.

Handler failures from `onOpen` or `onMessage` are reported through `onError`; `onClose` is invoked
once and the transport is released even if the close callback itself fails.

## Verification

`WebSocketOverH2Test` exercises the extended CONNECT exchange, fragmented text, masking, graceful
close, and a binary message larger than the initial one-mebibyte stream window.
`WebSocketParityTest` sends the same message through one route and handler over HTTP/1.1 and
HTTP/2 and compares the result byte for byte.
