# Transport Architecture (Phase 2)

Audience: contributors. This is the document Phase 3 onward extends as HTTP/2 grows a real
connection state machine behind the seam described here.

## Why this exists

Before Phase 2, `HttpServer` (563 lines) did bind, accept, virtual-thread dispatch, WebSocket
upgrade detection, WebSocket handshake, the WebSocket session loop, keep-alive detection, HTTP
response serialization, chunked encoding, hex encoding, and decimal encoding — eleven reasons to
change in one class (R6). It also held three `ThreadLocal`s that meant "one per connection" under
virtual threads, not "one per core" (`EX-06`), and used `synchronized` around blocking socket
writes in `WebSocketSession`, which pins a virtual thread's carrier on Java 21 (`EX-01`).

Phase 2 replaces it with named, single-responsibility components and the `ConnectionProtocol`
seam HTTP/2 will plug into starting Phase 8.

## Package layout

```
dev.relism.flash.transport
├── TransportFactory        composes everything below; ServerHandle.create()'s implementation (EX-34)
├── ListenerBinder           FlashConfiguration.Listener -> bound ServerSocket
├── BoundListener            record: the bound socket + whether it is TLS
├── TransportTuning          accept-thread count / backlog / socket buffer size constants
├── AcceptLoop                one listener's accept loop body
├── ConnectionRunner          per-connection setup/teardown: TLS handshake, protocol negotiation,
│                             dispatch to a ConnectionProtocol, guaranteed cleanup
├── ConnectionProtocol        the h1/h2 seam: void run(ConnectionContext)
├── ConnectionContext         everything a ConnectionProtocol needs, bundled (record)
├── ConnectionScratch         per-connection reusable buffers (EX-06's fix)
├── ScratchPool               a bounded cache of ConnectionScratch instances
├── ServerLifecycle           implements ServerHandle: start/startAndBlock/stop, graceful shutdown (EX-32)
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
TransportFactory.create(configuration, router, wsRouter)
    binds every configured listener (ListenerBinder)
    builds one ConnectionRunner (shared virtual-thread executor, ScratchPool, Http1Connection)
    returns a ServerLifecycle (implements ServerHandle)

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
        if negotiated == H2: return                  # no Http2Connection yet (Phase 8) -- close cleanly
        build ConnectionContext, dispatch to http1Protocol.run(ctx)
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

The router's own `ThreadLocal`s (`FastPathRouterImpl`, `FastPathWsRouterImpl`) are **not**
removed in this phase — `EX-06`'s registry entry explicitly phases that part of the fix to
Phase 4, where the router also gains the API surface change (a scratch parameter, or reading
from the request's context) needed to remove them correctly. See `DECISIONS.md` (`DEC-15`) for
why Phase 2's Definition of Done was corrected to say so explicitly rather than silently drift
from the registry.

## The `ConnectionProtocol` seam (R1 / `DEC-02`)

```java
public interface ConnectionProtocol {
    void run(ConnectionContext ctx) throws IOException;
}
```

`ConnectionRunner` decides h1 vs h2 exactly once, immediately after ALPN/preface detection, and
dispatches. Today only `Http1Connection` exists; an `H2` negotiation result is closed cleanly
(there is no `Http2Connection` to hand off to until Phase 8). Neither implementation is aware
the other exists — `dev.relism.flash.http1` and `dev.relism.flash.h2` do not import each other,
enforced by `PackageBoundaryTest`.

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

HTTP/2's half of this fix (a `GOAWAY` frame, RFC 9113 §6.8) lands in Phase 8.

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
