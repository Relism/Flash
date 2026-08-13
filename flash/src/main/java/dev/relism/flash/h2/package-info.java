/**
 * HTTP/2 (RFC 9113) and HPACK (RFC 7541), as a peer transport to HTTP/1.1 — not a special case
 * bolted onto it. This package lives in {@code flash} core, not an extension, because the
 * protocol decision is made at the transport layer, where {@code HttpServer}'s replacement
 * lives (see {@code DEC-01} in {@code flash/docs/http2/DECISIONS.md}).
 *
 * <h2>Architecture in one page</h2>
 *
 * <h3>The demux loop</h3>
 * One virtual thread per connection reads and dispatches frames
 * ({@code Http2Connection}, Phase 8): read a 9-byte frame header, validate it against the
 * per-type table ({@code FrameValidator}, Phase 5), dispatch by type. This loop <b>never blocks
 * on application work</b> — a slow handler must never stall frame processing for other streams
 * on the same connection, which is the entire point of multiplexing. The only things the demux
 * thread itself does synchronously are protocol bookkeeping: SETTINGS/PING/WINDOW_UPDATE
 * accounting, HPACK decode, and stream-table updates.
 *
 * <h3>Virtual-thread-per-stream dispatch</h3>
 * Once a request's headers (and, for small bodies, its body) are fully assembled, the demux
 * thread submits a task to the shared virtual-thread executor and returns immediately to
 * reading frames. Routing, middleware, and the user's handler run on that stream's own virtual
 * thread — identical to the HTTP/1.1 dispatch model, so a handler written for h1 works
 * unmodified over h2 (verified in Phase 10).
 *
 * <h3>The writer discipline</h3>
 * N stream threads share one socket. {@code Http2FrameWriter} (Phase 3) is the single
 * serialization point: a stream serializes its complete frame (header + HPACK block + payload)
 * into a reusable per-stream scratch buffer, then takes a connection-wide {@link
 * java.util.concurrent.locks.ReentrantLock} — never {@code synchronized}, which pins a virtual
 * thread's carrier on Java 21 (see {@code DEC-03}) — and issues one bulk write. The uncontended
 * path costs one CAS ({@code tryLock()}); contention falls back to an intrusive, allocation-free
 * MPSC queue rather than blocking every writer on the lock. This is the project's single
 * largest architectural risk and is proven or falsified by Phase 3's benchmark gate before any
 * frame-layer code is written.
 *
 * <h3>The arena strategy</h3>
 * HPACK is stateful compression: header bytes that enter the dynamic table must outlive the
 * connection read buffer, and Huffman-coded values must be decoded somewhere. Flash copies each
 * decoded header into a <b>per-stream arena</b>, not a shared one ({@code DEC-06}). This is not
 * the minimal-copy design — a refcounted shared dynamic table would copy less — but it is the
 * only design that is correct by construction under concurrent multiplexing: the demux thread
 * can decode another stream's HEADERS, evicting dynamic-table entries, while a handler on a
 * different virtual thread is still reading a view into a previous decode. A per-stream arena
 * makes that race impossible without any cross-thread coordination on the hot path. See
 * {@code flash/docs/http2/HPACK.md} (Phase 7) for the worked example.
 *
 * <h2>What this package deliberately does not implement</h2>
 * <ul>
 *   <li><b>Server push ({@code PUSH_PROMISE}).</b> Flash never sends it and rejects any
 *   {@code PUSH_PROMISE} received from a client as a connection error, since only servers may
 *   send it (RFC 9113 §8.4). Flash advertises {@code SETTINGS_ENABLE_PUSH = 0}. Justification:
 *   push is widely disabled by browsers and its cache-coherency benefits are better served by
 *   {@code 103 Early Hints} or resource hints, which do not require protocol-level state.</li>
 *   <li><b>Priority scheduling ({@code PRIORITY} frames, and the deprecated priority fields on
 *   {@code HEADERS}).</b> RFC 9113 §5.3.2 itself says endpoints "SHOULD ignore" priority
 *   signalling — it was deprecated in the same RFC that (re)defined HTTP/2. Flash parses and
 *   discards {@code PRIORITY} frames (they must still be consumed, not rejected) and never acts
 *   on the priority fields.</li>
 *   <li><b>{@code Upgrade: h2c}.</b> RFC 9113 §3.1 removed the HTTP/1.1 upgrade mechanism that
 *   RFC 7540 §3.2 defined. Cleartext HTTP/2 is reached only via prior knowledge (RFC 9113 §3.4),
 *   which is what every modern h2c client (notably gRPC) actually uses. See {@code DEC-10}.</li>
 * </ul>
 *
 * <h2>Package layout</h2>
 * This package is filled in incrementally, phase by phase — see
 * {@code flash/docs/http2/IMPLEMENTATION-PLAN.md} Part III for the full schedule. As of Phase 0
 * it contains only the error model ({@link dev.relism.flash.h2.Http2ErrorCode},
 * {@link dev.relism.flash.h2.Http2Exception}, {@link dev.relism.flash.h2.Http2StreamException})
 * and the limits registry ({@link dev.relism.flash.h2.Http2Limits}). Subpackages
 * {@code frame}, {@code hpack}, {@code stream}, {@code message}, and {@code upgrade} are added
 * by Phases 3, 5, 7–9, and 14–15 respectively.
 */
package dev.relism.flash.h2;
