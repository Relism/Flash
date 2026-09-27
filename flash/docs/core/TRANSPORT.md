# Transport architecture

Audience: contributors. This document describes the shared listener and connection layer behind
the HTTP/1.1 and HTTP/2 implementations.

## Why this exists

The original `HttpServer` (563 lines) did bind, accept, virtual-thread dispatch, WebSocket
upgrade detection, WebSocket handshake, the WebSocket session loop, keep-alive detection, HTTP
response serialization, chunked encoding, hex encoding, and decimal encoding — eleven reasons to
change in one class (R6). It also held three `ThreadLocal`s that meant "one per connection" under
virtual threads, not "one per core" (`EX-06`), and used `synchronized` around blocking socket
writes in `WebSocketSession`, which pins a virtual thread's carrier on Java 21 (`EX-01`).

The current design replaces it with named, single-responsibility components and a
`ConnectionProtocol` seam implemented by both wire protocols.

## Package layout

```
dev.relism.flash.transport
├── TransportFactory        composes everything below; what Server.start binds through (EX-34)
├── ListenerBinder           ServerConfig.Listener -> bound ServerSocket
├── BoundListener            record: the bound socket + whether it is TLS
├── TransportTuning          accept-thread count / backlog / socket buffer size constants
├── AcceptLoop                one listener's accept loop body
├── ConnectionRunner          per-connection setup/teardown: TLS handshake, protocol negotiation,
│                             dispatch to a ConnectionProtocol, guaranteed cleanup
├── ConnectionProtocol        the h1/h2 seam: void run(ConnectionContext)
├── ConnectionContext         everything a ConnectionProtocol needs, bundled (record)
├── ConnectionScratch         per-connection reusable buffers (EX-06's fix)
├── ScratchPool               a bounded cache of ConnectionScratch instances
├── ServerLifecycle           start/await/stop, graceful shutdown; what Server wraps (EX-32)
├── BufferedByteSource        the buffered, deadline-aware, peekable inbound-byte source (Phase 1, EX-10)
└── ProtocolNegotiator/NegotiatedProtocol   ALPN + h2c preface detection (Phase 1)

dev.relism.flash.http1
├── Http1Connection            implements ConnectionProtocol: the h1 keep-alive request loop
├── Http1ResponseWriter        serializes a Response as an HTTP/1.1 message
└── Http1KeepAlive             keep-alive decision + the shared Connection-header token scanner (EX-13)

dev.relism.flash.websocket (existing package, extended)
├── WebSocketUpgrade           upgrade detection + handshake response
├── WebSocketLoop               the session read/dispatch loop
├── WebSocketSession            per-connection WS I/O (frame codec + send API), EX-01/EX-11/EX-12
└── WebSocketProtocolException  RFC 6455 violation, carries the correct close code
```

## The connection lifecycle

```
TransportFactory.create(configuration, router)
    binds every configured listener (ListenerBinder)
    builds one ConnectionRunner (shared virtual-thread executor, ScratchPool, both protocols)
    returns a ServerLifecycle

ServerLifecycle.start()
    for each listener, spawns TransportTuning.ACCEPT_THREADS platform threads
    each runs AcceptLoop.run(listener, runner, this::isStopped)

AcceptLoop.run(...)
    loop: listener.socket().accept() -> runner.accept(socket, stopped)

ConnectionRunner.accept(socket, stopped)
    submits to the virtual-thread executor -> handle(socket, stopped)

ConnectionRunner.handle(socket, stopped)
    activeSockets.add(socket); scratch = scratchPool.acquire()
    try:
        configure TCP_NODELAY / send buffer size
        if SSLSocket: force startHandshake() under headerReadTimeoutMs (EX-30)
        wrap streams: BufferedByteSource in, buffered OutputStream out, raw OutputStream rawOut
        negotiated = negotiateProtocol(socket, in)   # ALPN or h2c preface
        build ConnectionContext
        dispatch to http1Protocol.run(ctx) or http2Protocol.run(ctx)
    finally:
        activeSockets.remove(socket); scratchPool.release(scratch)
```

`Http1Connection.run(ConnectionContext)` is where HTTP/1.1 semantics actually live: the
keep-alive loop, the idle/header/body deadline transitions (Phase 1), the `MalformedRequestException`
rejection path, the WebSocket upgrade handoff, and the response write.

## `ConnectionScratch` and `ScratchPool` (`EX-06`)

`ThreadLocal` is the right idiom when "one per thread" means "one per core" — a bounded
platform-thread pool. Flash runs one **virtual** thread per connection
(`Executors.newVirtualThreadPerTaskExecutor()`), so a `ThreadLocal` there means one per
*connection*, with no upper bound: at 100 000 concurrent connections, an 8 KB relay buffer alone
would be ~800 MB that a bounded pool would otherwise cap.

`ConnectionScratch` is therefore an explicit, plain object (decimal-encoding buffer, streaming
relay buffer, the WebSocket-handshake `MessageDigest`) acquired from a `ScratchPool` at
connection start and released at connection end. The pool is a bounded *cache*, not a
leak-free arena: above its bound (`min(availableProcessors * 64, 4096)` by default), a released
scratch is simply dropped for the garbage collector rather than queued, so an unusually large
burst of connections cannot grow it without limit.

The router uses an explicit per-connection scratch passed through `Router.dispatch`; it retains no
connection state in a `ThreadLocal`.

## The `ConnectionProtocol` seam (R1 / `DEC-02`)

```java
public interface ConnectionProtocol {
    void run(ConnectionContext ctx) throws IOException;
}
```

`ConnectionRunner` decides h1 vs h2 exactly once, immediately after ALPN/preface detection, and
dispatches to `Http1Connection` or `Http2Connection`. Neither implementation is aware the other
exists — `dev.relism.flash.http1` and `dev.relism.flash.http2` do not import each other, enforced
by `PackageBoundaryTest`.

## Graceful shutdown (`EX-32`)

Two stages, driven by `ServerLifecycle.stop()`:

1. **Stop accepting.** Every listener socket is closed immediately; `stopped` flips to `true`.
2. **Drain, then force-close.** `Http1Connection`'s request loop checks `ctx.stopped()` twice:
   once before waiting for the next request (exits immediately if already stopped, rather than
   waiting out the idle-keep-alive timeout), and again right before writing the *current*
   response — forcing `Connection: close` on it even if the response's own `Connection` header
   logic would have said keep-alive, and even if shutdown began *while the handler was running*
   (the common case). `ServerLifecycle.stop()` polls `activeSockets` for up to
   `shutdownDrainTimeoutMs`, then force-closes whatever remains and shuts down the executor.

HTTP/2 shutdown sends the two-stage `GOAWAY` sequence from RFC 9113 §6.8 before the lifecycle's
drain deadline force-closes remaining sockets.

## What changed for WebSocket (`EX-01`, `EX-11`, `EX-12`, `EX-13`)

- **`EX-01`**: `WebSocketSession`'s two blocking-write sites (`close`, `writeFrame`) now
  serialize on a `ReentrantLock` instead of `synchronized (out)` — a virtual thread blocking
  inside `synchronized` pins its carrier platform thread on Java 21 (JEP 491, which removes
  this, is JDK 24+). `ReentrantLock` unmounts the blocked virtual thread instead.
- **`EX-11`**: `readFrame` used to read the extended-length and mask-key bytes one at a time.
  It now reads that whole variable-length remainder in a single bounded `readFully` into the
  existing `hdrScratch` array, then decodes with shifts.
- **`EX-12`**: `readFrame` now reassembles continuation frames into one logical message (bounded
  by the same buffer a single frame already had), enforces the masking direction RFC 6455 §5.1
  requires for this session's role, validates the opcode against the RFC's defined set, enforces
  control-frame constraints (not fragmented, ≤125 bytes), and reports violations via
  `WebSocketProtocolException` carrying the correct close code (1002 protocol error, 1009
  message too big) for `WebSocketLoop` to send before closing.
- **`EX-13`**: the `Connection` header is a comma-separated token list, not a single value —
  `Http1KeepAlive.tokenListContains` is the one scanner both the keep-alive decision and
  `WebSocketUpgrade`'s `Connection: Upgrade` check use, so they cannot drift apart again.
