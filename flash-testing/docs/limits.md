# Limits

What `flash-testing` deliberately cannot do, why, and what to use instead. Each of these is a
consequence of a real constraint, not an unfinished feature.

## TLS

`config(cfg -> cfg.tls(...))` is rejected. The harness serves plaintext on loopback and hands you
a client bound to `http://127.0.0.1:<port>`; a TLS listener would leave that base URI pointing at
the wrong scheme, and the client would need the test certificate in a trust store.

**Instead:** start the app directly with `Server.start(app, config)` and a raw `SSLSocket`, as
`HttpServerTlsTest` does. `port(0)` plus `server.port()` still removes the free-port dance.

Lifting this is the only limit here worth reconsidering, and only if application-level TLS testing
is actually wanted — transport-level TLS is already covered by the core suite.

## HTTP/2

`java.net.http` reaches cleartext h2 through an `Upgrade:` handshake. Flash implements HTTP/2
cleartext by **prior knowledge only** — a deliberate choice recorded in the root README — so the
harness client cannot negotiate h2 against a plaintext Flash listener. Over TLS it would work
through ALPN, but TLS is unavailable per the section above.

**Instead:** the `flash/src/test/.../http2/` suites drive h2 frames over raw sockets. That is the
right tool for protocol behaviour anyway.

## WebSocket over HTTP/2

`java.net.http.WebSocket` does not negotiate RFC 8441 extended CONNECT, so `FlashWebSocket` always
speaks the HTTP/1.1 upgrade. Flash supports both, but this client can only exercise one.

**Instead:** `WebSocketOverH2Test` and `WebSocketParityTest` frame extended CONNECT by hand.

## Malformed requests

Every request goes through `java.net.http`, which structurally cannot emit an invalid request
line, a bad header block, a smuggled `Content-Length`, or a chunked *request* body on demand. That
is a feature for application testing and a blocker for parser testing.

**Instead:** `RequestParserSecurityTest`, `RequestParserFuzzTest` and the raw-socket half of
`HttpServerTest` write bytes directly. A harness that could send malformed requests would just be
a socket.

## Response framing

`java.net.http` transparently decodes chunked responses and hides connection reuse, so
`Transfer-Encoding: chunked` and `Connection: keep-alive` are not observable through
`FlashResponse`.

**Instead:** `HttpServerTest` keeps raw sockets for exactly those assertions, taking its port from
a `FlashTest` field so the class still boots once. Mixing the two styles in one class is the
intended pattern, not a workaround.

## The `flash` core module

`flash-testing` depends on `flash`, so `flash`'s own tests cannot depend on `flash-testing` —
Maven rejects module cycles regardless of scope.

**Instead:** core tests use `Server.start(...)` with `port(0)` and read `port()` back. Every
core suite already does this. Unblocking it would need a third module depending on both, which is
not worth it for the two suites that would benefit.

## Shutdown draining

The harness pins `shutdownDrainTimeoutMs` to 250ms after `config` runs, so a test cannot
exercise graceful-drain behaviour through it.

**Instead:** `ServerLifecycleGracefulShutdownTest` builds its app directly. An escape hatch
would be easy to add if this ever comes up twice.
