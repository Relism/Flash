# Flash — HTTP/2 Implementation Plan

> **Status**: working implementation ledger; it is not product documentation or an API contract.
> **Target branch**: `feature/core/http2`
> **Target module**: `flash` (core). HTTP/2 is a transport concern and must live where
> `HttpServer` lives; it cannot be an extension.
> **Target package root**: `dev.relism.flash.http2`
> **Java baseline**: 21 (`maven.compiler.source/target=21` in the root `pom.xml`). Every
> decision in this document assumes Java 21 semantics, in particular that
> **`synchronized` pins the carrier thread of a virtual thread** (JEP 491, which removes
> pinning, only lands in JDK 24 — we cannot rely on it).

---

## How to read this document

This plan is written for an agent (or engineer) who will implement it end to end, possibly
across many sessions, without further clarification. It is deliberately verbose and
deliberately repetitive: **every phase restates the constraints it must satisfy**, so that a
phase can be picked up in isolation without re-reading the whole document.

Structure:

- **Part I** — Non-negotiable rules that apply to every phase.
- **Part II** — The defect/optimization registry (`EX-nn`) for **existing** code. These are
  real problems found by reading the current codebase. Each is assigned to a phase.
- **Part III** — The phases themselves, in strict dependency order.
- **Part IV** — Testing strategy.
- **Part V** — Documentation deliverables.
- **Part VI** — Appendices: RFC constant tables, checklists, decision log.

Every phase has:

| Field | Meaning |
|---|---|
| **Goal** | One sentence. What exists after this phase that did not before. |
| **Why now** | Dependency justification. Why this phase cannot come later or earlier. |
| **Files** | Created / modified / deleted, with full paths. |
| **Tasks** | Numbered, atomic, verifiable. |
| **EX items** | Existing-code defects addressed in this phase. |
| **Zero-alloc contract** | What must allocate zero on the steady-state path, and what may not. |
| **Safety checks** | Validation that must be present. Omission is a bug, not a TODO. |
| **Tests** | What must be green before the phase is considered done. |
| **Docs** | Documentation that must be written/updated in the same PR. |
| **DoD** | Definition of Done — a binary checklist. |

**Nothing in a phase's DoD may be deferred to a later phase.** If a task turns out to be
bigger than expected, split the phase; do not carry debt forward.

---

## Progress Ledger

This table is the single source of truth for where the project stands. It is updated **at the
moment** work happens, not at the end of a session: mark a phase `in progress` when it is
started, tick DoD checkboxes as they are actually verified, and update the `Notes` column with
the exact resume point — task number, file, what is missing — whenever a phase is left
incomplete. Anyone picking this up cold must be able to continue from the `Notes` column alone.

Status values: `not started` / `in progress` / `blocked` / `done`.

| Phase | Status | Branch/PR | Notes |
|---|---|---|---|
| 0 — Groundwork | done | `feature/core/http2` | Package skeleton, `Http2Limits`, `Http1Limits`, `Http2ErrorCode`, `Http2Exception`/`Http2StreamException`, and `DECISIONS.md`. 226/226 tests green. |
| 1 — HTTP/1.1 hardening + ALPN/preface | done | `feature/core/http2` | EX-02/03/07/08/10/17/18/30/31 fixed; EX-35/36 found+fixed. `BufferedByteSource`, `ProtocolNegotiator`, `MalformedRequestException` added (plan corrected, DEC-12). 277/277 tests green (run twice). h1 benchmark check deferred — no JMH harness until Phase 3 (documented in DoD). |
| 2 — Transport decomposition | done | `feature/core/http2` | `HttpServer.java` deleted; `transport`/`http1` packages + WS extraction (EX-01/06/11/12/13/14/15/16/32/34) done. Router `ThreadLocal` (EX-06 router half) deliberately deferred to Phase 4 per DEC-15. 311/311 tests green (run 3×). h1 benchmark check deferred — no JMH harness until Phase 3. |
| 3 — Serialized frame writer (GO/NO-GO gate) | done | `feature/core/http2` | `Http2FrameWriter`/`WriteIntent`/`IntrusiveMpscQueue` + `Http2FrameWriterTest`/`Http2FrameWriterStressTest` + `FrameWriterBenchmark` (JMH, `-Pjmh`, `src/jmh/java` — moved there from `src/test/java` after it broke default `mvn test`; see `DEC-17`). All 4 gate criteria met: N=1 0 B/op & 42.6 ns overhead (≤50 ns budget); N=64 65.5% throughput retention (≥60%) & 11.8–14.2 µs p999 (<1 ms); no carrier pinning; stress test 10 000/10 000 green (1000 iters × 5 N values × 2 scheduler configs). Full numbers in `WRITER.md`, `DEC-09`. 321/321 non-JMH tests green. |
| 4 — Byte-layer foundations | done | `feature/core/http2` | `dev.relism.flash.bytes` package (`ByteScan`+SWAR, `ArrayBackedByteView`, `SegmentedByteView`, `PooledSlice`/`SlicePool`, `ByteWriter`, `Pairs`) built. `EX-04`/`EX-05`/`EX-09`/`EX-19`/`EX-25`/`EX-26`/`EX-33` done, plus `EX-06`'s router half (plan correction, `DEC-19`) removing `FastPathRouterImpl`/`FastPathWsRouterImpl`'s `ThreadLocal`s via an opaque per-connection scratch (`AbstractRouter#newScratch`) instead of extending `ConnectionScratch` (would have created a `routing`→`transport` package cycle). `AbstractRouter`/`AbstractWsRouter.route()` gained a `scratch` param — all call sites updated. Measured (`DEC-20`): SWAR scan 35.4% faster (kept), `EX-04`'s word-path 32.1% faster at the mechanism level (kept; today's router doesn't route through it — `MethodPathByteView` stays non-array-backed by design). Router matching itself is ≈0 B/op including parametric routes. Full h1 pipeline is 120.008 B/op, 100% attributable to `Request`/`RequestBody`/`RequestLine` construction — explicitly Phase 6 scope, not a Phase 4 regression. Two documented (non-hot-path) anonymous-`ByteView` fallbacks remain in `QueryParams`/`PathParams.view`. `BYTES.md` written. 395/395 tests green (both with and without `-Pjmh`). |
| 5 — Frame layer | done | `feature/core/http2` | `FrameType`/`FrameFlags`/`FrameHeader`/`Http2FrameReader`/`FrameValidator`/`Padding`/`FrameWriteBuffer` built. All 10 frame types read/validated/written; per-type RFC error codes verified individually (`FrameValidatorTest`); fuzz-tested 10M random inputs (~14s, green). Zero-alloc contract measured, not asserted: read+validate+consume 0.002 B/op, write ≈10⁻⁴ B/op (`DEC-21`). Found+fixed `EX-37` (`BufferedByteSource`'s deadline mechanism NPE'd against a `null` socket — zero prior test coverage of `EX-07`'s own fix; added `BufferedByteSourceTest`). `FRAMES.md` written. 449/449 tests green. |
| 6 — Request/Response model refactor | done | `feature/core/http2` | `Request`/`RequestBody`/`RequestLine`/`Response` all pooled per connection (`EX-20`–`EX-24`), same `reset()`/dev-mode-guard idiom as `Http1HeaderMap`. `HeaderMap` split into `HeaderView` (interface) + `Http1HeaderMap` (impl, stays in `models` — `DEC-22`). `Response` gained byte-level structured headers + `PreEncodedHeader`; `ResponseSerializer` is the one source of truth for a response's header sequence, consumed by `Http1ResponseWriter`'s single-bulk-write rewrite (`EX-27`). `ByteTemplate` fixed to O(1) slot lookup + a buffer-writing overload (`EX-28`). `Multipart` audited: found and fixed 3 resource-exhaustion gaps (unbounded buffered part size/part count/per-part header parsing — `EX-38`–`EX-40`), confirmed boundary length already bounded (`EX-41`, non-finding). Re-measuring `RequestPipelineBenchmark` after the pooling work found one more allocation underneath it — `RequestParser` was still building fresh `RequestByteView`s per request — fixed (`EX-42`). Zero-alloc contract closed: `parseAndRoute` 120.008 → 0.008 B/op (`DEC-20`/`DEC-23`). Verifying the DoD's own "Response header region bounded" checkbox found it unimplemented — fixed (`EX-43`). `MESSAGE-MODEL.md` written; README gained an "Object lifetime" section. 503/503 tests green. |
| 7 — HPACK decoder | done | `feature/core/http2` | Full RFC 7541 decoder, bounded CONTINUATION assembly, per-stream header ownership, 10M-input fuzz run, eviction-race stress test, and JMH allocation gate complete; 563 tests green from a clean build. |
| 8 — Connection state machine | done | `feature/core/http2` | Preface, transactional SETTINGS, priority PING ACK, connection WINDOW_UPDATE, two-stage GOAWAY, per-socket transport/ALPN dispatch, HPACK block composition, clean curl handshake, h2spec 28/35 selected cases and 0.008 B/op JMH gate complete. Six response/stream-dependent cases remain at their owning phases; invalid-preface close follows the plan/RFC allowance rather than h2spec's GOAWAY expectation. |
| 9 — HPACK encoder + h2 response path | done | `feature/core/http2` | Stateless static-table HPACK encoder; precompiled status/content-type/date fields; reusable response writer with header filtering, bounds, CONTINUATION splitting and fixed DATA happy path; HTTP/1/2 serializer parity test. EX-46 fixed the one-digit Date day-of-month bug. JMH: 174.309 ns/op, 0.001 B/op (noise floor), no GC. 603/603 tests green from a clean `-Pjmh` build. |
| 10 — Stream state machine + dispatch | done | `feature/core/http2` | Explicit stream transition table, bounded primitive stream table and pool, pseudo-header/message validation, protocol-neutral `Request` assembly, virtual-thread dispatch and exception path, cancellation-safe release, raw h2c + Java HTTP/2 integration. h2spec sections 5/8: 37/39; the two content-length/DATA accounting cases are owned by Phase 11. JMH pooled lifecycle: 458.499 ns/op, 0.003 B/op, no GC. 618/618 tests green from a clean `-Pjmh` build. |
| 11 — DATA, flow control, bodies | not started | — | — |
| 12 — Trailers, half-close, gRPC | not started | — | — |
| 13 — Security hardening & abuse resistance | not started | — | — |
| 14 — h2c prior knowledge + proxy support | not started | — | — |
| 15 — RFC 8441 extended CONNECT (WS over h2) | not started | — | — |
| 16 — Compliance test suite | not started | — | — |
| 17 — Benchmarks, allocation gates, tuning | not started | — | — |
| 18 — Documentation | not started | — | — |

---

# PART I — Non-negotiable rules

These apply to **every line of code written or touched** by this plan, including refactors of
existing code.

## R1. Coexistence, not monkey-patching

HTTP/1.1 and HTTP/2 are two peers of the same abstraction, not a base case and a special case.

- **No `if (isHttp2)` branches inside HTTP/1.1 code paths.** The protocol decision is made
  **once**, immediately after ALPN/preface detection, and dispatches to a
  `ConnectionProtocol` implementation. After that point neither implementation knows the
  other exists.
- The HTTP/1.1 code path after this work must be **measurably no slower** than before it.
  This is enforced by benchmark gates (Phase 17). If an abstraction costs h1 throughput, the
  abstraction is wrong, not the benchmark.
- Shared code (byte scanning, buffer pools, the writer discipline, `Request`/`Response`) is
  **extracted upward** into protocol-neutral components, never **pushed sideways** with
  protocol flags.

## R2. Zero allocation on the steady-state path

"Steady state" means: the connection is established, buffers/pools are warm, and a
request/response cycle is being served on an already-open connection.

Allowed to allocate:
- Connection setup (once per TCP connection).
- Pool growth (amortized to zero).
- Explicit user-facing conversions (`Request.path()`, `HeaderMap.first()`, `PathParams.get()`)
  — these are documented as allocating and the user opts into them.
- Error paths that terminate the connection.

Forbidden to allocate on the steady-state path:
- Any frame object, header object, view object, param object, list, iterator, lambda capture,
  boxed primitive, varargs array, or `String`.
- Anonymous inner classes created per call (this is currently violated — see `EX-05`).
- `InputStream`/`OutputStream` wrappers created per request (currently violated — `EX-29`).

**Verification**: Phase 17 adds a JMH `-prof gc` gate. `gc.alloc.rate.norm` must be
**0 B/op** for the canonical happy paths (h1 GET, h2 GET, h2 unary POST with small body).
A non-zero value fails CI.

## R3. Zero copy where the protocol permits it, and honest naming where it does not

- HTTP/1.1: request bytes are a contiguous range of the connection read buffer. Views are
  slices. This is genuinely zero-copy and stays that way.
- HTTP/2 headers: HPACK is a **stateful compression protocol**. Values entering the dynamic
  table must outlive the read buffer, and Huffman-coded values must be decoded somewhere.
  Copies are mandatory. Do not pretend otherwise in code comments or docs.
  The honest formulation, which must be used in documentation:
  > *HTTP/1.1 copies nothing per request but re-scans every header on every request.
  > HTTP/2 copies each novel header once per connection and then references it by index.
  > Over a connection of realistic length, HTTP/2 does strictly less total work.*
- HTTP/2 DATA: payload must be transferred out of the shared read buffer, because holding it
  would head-of-line-block the whole connection — which is the exact thing HTTP/2 exists to
  prevent. This is a **pooled buffer handoff**, not an allocation.

## R4. Everything constant is precompiled at boot

If a byte sequence is derivable from a compile-time-constant set, it is computed **once** in a
static initializer or enum constructor and never again. The codebase already does this
(`HttpStatus.bytes`, `ContentType.bytes`, `HttpMethod.bytes`, `AbstractRouter.JSON_404`) — the
h2 work extends it, it does not introduce it.

Mandatory precompilation targets introduced by this plan:
- HPACK static-table encodings for every `HttpStatus` constant.
- HPACK-encoded, Huffman-compressed `content-type` field lines for every `ContentType` constant.
- The HPACK Huffman encode LUT and decode FSM tables.
- The HTTP/2 connection preface bytes, all SETTINGS frames we ever send, the SETTINGS ACK
  frame, the PING ACK template, and all GOAWAY frames with a constant error code.
- The `Date` header value, refreshed once per second by a single shared daemon thread, not
  formatted per response (`EX-33`).

## R5. Bit-level and word-level operations

- Frame headers are decoded with explicit shifts and masks, never via `ByteBuffer` or
  `DataInputStream`.
- Multi-byte scans over array-backed data use `VarHandle`-based `long` reads (SWAR) where the
  scan is longer than 8 bytes. `fpr-core` already ships this technique in
  `dev.relism.fpr.core.internal.runtime.ByteCompare` (it holds a `LONG_VIEW` `VarHandle`);
  Flash currently never enables it (see `EX-04`). This plan enables it.
- Integer packing of two `int`s into a `long` (the `(hi << 32) | lo` idiom already used in
  `HeaderMap.findFirst` and `QueryParams.findFirst`) is the accepted way to return a pair
  without allocating. Keep it, and add a small documented helper so the shifts are not
  duplicated in five places.

## R6. No god classes

A class has **one reason to change**. Concretely, for this codebase:

- `HttpServer` (currently 563 lines) does bind, accept loop, lifecycle, virtual-thread
  dispatch, WebSocket upgrade detection, WebSocket handshake, WebSocket session loop,
  keep-alive detection, HTTP response serialization, chunked encoding, hex encoding, and
  decimal encoding. That is eleven reasons to change. It is decomposed in Phase 2.
- Every new h2 class has a single, nameable responsibility. If you cannot name it in four
  words without "and", split it.
- Soft guidance: a class over ~250 lines, or with more than one clearly separable state
  machine, is a smell. This is guidance, not a lint rule — a 300-line class that is one
  cohesive state machine (e.g. `Http2StreamState`) is fine; a 150-line class doing two things
  is not.

## R7. Readability is a hard requirement, not a trade-off

The existing codebase has an unusually high standard of Javadoc: it explains *why*, documents
lifetime contracts (`HeaderMap` lines 15–31 is the reference example), and calls out the
allocation model explicitly (`HttpServer` lines 49–59). **Match that standard.** Specifically:

- Every public type gets a class-level Javadoc explaining its role and its **lifetime and
  thread-safety contract**.
- Every zero-alloc trick gets a comment explaining what it avoids and why the obvious code
  would be worse. A bare `long r = findFirst(name)` with no explanation is not acceptable.
- Every RFC-mandated behaviour cites the section: `// RFC 9113 §6.10 — CONTINUATION frames
  MUST NOT be interleaved`. This is how the compliance suite stays auditable.
- Every deviation from the RFC (there will be a few, e.g. "we never emit PUSH_PROMISE") is
  documented with the justification and the RFC's own permission for it.

## R8. Safety checks are features

Any place that reads a length, an index, a count, or a size from the network gets an explicit
bound check with a named limit constant, and a named error path. "The buffer would have
thrown `ArrayIndexOutOfBoundsException`" is not a safety check — it is an uncaught exception
that leaks a stack trace and kills a connection with the wrong error code.

Every limit is a constant on a single `Http2Limits` class (h2) or `Http1Limits` class (h1),
each with a Javadoc explaining the attack it prevents and the RFC/CVE reference.

## R9. Commit and branch discipline

Per `AGENTS.md`:
- Branch: `feature/core/http2` (already created). Sub-work stays on this branch or on
  short-lived branches off it named `feature/core/http2-<phase-slug>`.
- Commits: Conventional Commits with scope `core`, e.g.
  `feat(core): add HPACK Huffman decoder`, `refactor(core): split HttpServer into transport
  components`, `fix(core): reject Content-Length with Transfer-Encoding`.
- Never edit `<version>` in any POM. Never push to `master`. Every phase lands via PR with
  green CI.
- If the `AGENTS.md` allowed-scope list needs `h2`, that is a separate `docs:` commit; until
  then use `core`.

## R10. When you find a problem in existing code, fix it

This is an explicit instruction from the project owner and overrides any instinct to minimize
diff size.

While implementing any phase, if you find that existing code:
- does something extra that is not needed,
- lacks a safety check,
- allocates where it could not,
- could be precompiled at boot,
- has a correctness or protocol-compliance bug,
- or is structured in a way that blocks the phase,

then **fix it in that phase**, add it to the registry in Part II with a new `EX-nn` id,
document it in the PR description, and add a regression test. Do not open a TODO. Do not
"leave it for later". The registry in Part II is a starting point found by reading the code
once — it is explicitly expected to grow.

---

# PART II — Existing-code defect & optimization registry

Found by reading the current `master`. Each entry has an owner phase. Entries marked
**BLOCKER** must be fixed before the phase that depends on them can proceed.

## Critical — correctness / security

### EX-01 — `synchronized` on the WebSocket write path pins carrier threads · **BLOCKER for Phase 3**
`WebSocketSession.writeFrame` (`websocket/WebSocketSession.java:207`) and
`WebSocketSession.close` (line 112) hold `synchronized (out)` across a **blocking socket
write**. On Java 21 a virtual thread that blocks inside a `synchronized` block **pins its
carrier platform thread**. With WebSocket this is tolerable (one session, one thread, near-zero
contention). With HTTP/2 the same pattern applied to a shared connection writer with N
concurrent streams would pin carriers en masse and starve the scheduler under exactly the load
h2 exists to serve.
**Fix**: replace with `java.util.concurrent.locks.ReentrantLock`, which is virtual-thread aware
(a blocked virtual thread unmounts). Applies to WebSocket now and sets the precedent the h2
writer must follow. **Never introduce a new `synchronized` block that can block on I/O.**
**Phase**: 2 (as part of the WebSocket extraction).

### EX-02 — Request smuggling: `Content-Length` + `Transfer-Encoding` accepted together
`RequestParser.parse` (`RequestParser.java:146-162`) reads both headers into local variables and
lets `isChunked` win, but never rejects the combination. RFC 9112 §6.1 requires that a message
with both is treated as an error by an origin server (it is the canonical CL.TE/TE.CL smuggling
vector, particularly dangerous once Flash is used as a proxy in Pathway).
**Fix**: if both are present → `400 Bad Request`, close connection. Also reject: multiple
`Content-Length` header lines with differing values; any `Transfer-Encoding` whose final coding
is not `chunked`.
**Phase**: 1.

### EX-03 — `RequestParser.parseLong` silently accepts malformed values
`RequestParser.java:222-229` skips any non-digit character instead of rejecting it.
`Content-Length: 5abc` parses as `5`; `Content-Length: -1` parses as `1`;
`Content-Length: 99999999999999999999` silently overflows. Combined with `EX-02` this is a
smuggling primitive.
**Fix**: strict parse — reject empty, reject any non-digit, reject leading `+`/`-`, reject
overflow past `Long.MAX_VALUE`, reject values above a configured
`Http1Limits.MAX_CONTENT_LENGTH`. Return a sentinel and raise `400`.
**Phase**: 1.

### EX-04 — `supportsLong()` is never implemented, so `fpr-core`'s word-at-a-time path is dead
Decompiled `fpr-core-1.1.1`:
```
public default boolean supportsLong();  →  iconst_0; ireturn          // always false
public default long longAt(int);        →  throw new UnsupportedOperationException
```
No Flash implementation overrides them: not `FastPathViews.RequestByteView`, not
`MethodPathByteView`, not `HeaderMap.Slice` (line 101), not the anonymous view in
`HeaderMap.view` (line 173). `ByteCompare` holds a `VarHandle LONG_VIEW` for 8-byte-at-a-time
comparison that Flash has **never once executed**.
**Fix**: implement `supportsLong()`/`longAt(int)` on every array-backed contiguous view
(`RequestByteView`, `SocketByteView`, `StringByteView`, `HeaderMap.Slice`, the `HeaderMap.view`
result once it is pooled). `MethodPathByteView` and any future segmented view keep the
`false` default. This is a free throughput win on the **existing** HTTP/1.1 router path and it
must be measured before/after.
**Phase**: 4.

### EX-05 — `HeaderMap.view(String)` allocates an anonymous `ByteView` per call
`models/HeaderMap.java:169-177` returns `new ByteView() { ... }` — one allocation plus a
capturing instance per call. `HttpServer.isWebSocketUpgrade` calls it twice per WebSocket
upgrade, and every middleware that inspects a header via `view()` pays it per request.
The same class already solves this correctly for `forEach` (lines 66-86: two reusable `Slice`
instances repositioned in place). Apply the same idiom.
**Fix**: a small pool of reusable `Slice` instances owned by the `HeaderMap`, handed out
round-robin, with the lifetime contract documented (valid until the next `view()` call that
wraps around, or the end of the request — whichever comes first). Same treatment for
`QueryParams.view` (line 32) and `PathParams.view` (line 44).
**Phase**: 4.

### EX-06 — `ThreadLocal` + virtual threads = per-connection memory, not per-core memory · **BLOCKER for Phase 3**
This is the single worst existing issue and its Javadoc is actively misleading.

`HttpServer.java:137-156` declares:
- `ThreadLocal<MessageDigest> SHA1` — Javadoc claims *"one per accept thread (there are now
  ACCEPT_THREADS of them, not one)"*. **This is false.** `performHandshake` runs inside the
  lambda submitted to `executorService` (line 275), i.e. on a **virtual thread**, one per
  connection. So it is one `MessageDigest` per connection, not one per accept thread.
- `ThreadLocal<byte[]> LONG_BUF` (20 B) and `ThreadLocal<byte[]> STREAM_RELAY_BUFFER` (8 KB) —
  same story. The class Javadoc (lines 49-59) frames these as a saving ("per-connection, not
  per-request"), which is true, but omits that with virtual threads *per-thread means
  per-connection* and there is no upper bound on connections.

`FastPathRouterImpl.FastPathRouterContext` (lines 26-39) is worse:
`ThreadLocal.withInitial(() -> new MatchResult<>(32, 128))` plus a `MethodPathByteView`, both
per virtual thread, i.e. **per connection**.

At 100 000 concurrent connections the `STREAM_RELAY_BUFFER` alone is ~800 MB, and the
`MatchResult(32,128)` instances add hundreds of MB more. `ThreadLocal` is the correct idiom for
platform-thread pools and the **wrong** idiom for virtual threads.

**Fix**: introduce an explicit, pooled `ConnectionScratch` object allocated once per connection
in the connection runner and passed down the call chain (or carried on the connection context
object). It owns: the decimal buffer, the relay buffer, the `MessageDigest`, the router
`MatchResult`, the combined method+path view, and — once Phase 5+ lands — the h2 encode
scratch, HPACK scratch and body-buffer free list. Scratch objects are returned to a bounded
global pool on connection close so that a burst of 100 k connections does not leave 100 k
scratches resident.
This refactor is **required** by h2 anyway (the h2 connection needs exactly such an object), so
it is not incidental work — it is the same work.
**Phase**: 2 (introduce), 3 (h2 consumes it), 4 (router consumes it).

### EX-07 — No socket read timeout: slowloris
Neither `HttpServer.bind` nor `HttpServer.process` ever calls `Socket.setSoTimeout`. A client
that opens a connection and sends one byte per minute holds a virtual thread, a
`RequestParser`, its buffer, and a socket forever. There is also no header-read deadline and no
idle keep-alive timeout.
**Fix**: three configurable timeouts on `FlashConfiguration`, all with sane defaults:
`headerReadTimeoutMs` (default 10 000), `idleKeepAliveTimeoutMs` (default 60 000),
`bodyReadTimeoutMs` (default 30 000). Enforced via `setSoTimeout` plus explicit deadline
tracking where `setSoTimeout` is insufficient (it resets per read).
**Phase**: 1.

### EX-08 — No limit on header count or individual header size
`RequestParser` bounds only the **total** header block via `maxHeaderBufferSize` (64 KB
default). A request with 60 000 one-byte headers passes, and every subsequent
`HeaderMap.first()` lookup then scans all of them (see `EX-09`), turning a 64 KB request into
quadratic CPU work per middleware.
**Fix**: `Http1Limits.MAX_HEADER_COUNT` (default 100), `MAX_HEADER_NAME_LENGTH` (default 256),
`MAX_HEADER_VALUE_LENGTH` (default 8192), `MAX_REQUEST_LINE_LENGTH` (default 8192, separate
from the total buffer). Each with a Javadoc naming the attack.
**Phase**: 1.

### EX-09 — `HeaderMap` lookups are O(headers) each, and the request path does many of them
`HeaderMap.findFirst` (line 180) rescans the entire header section per lookup. A single request
through a realistic middleware chain (OIDC reads `Authorization` and `Cookie`; the limiter
reads `X-Forwarded-For`; CORS reads `Origin`; the server reads `Connection`, `Upgrade`,
`Sec-WebSocket-Key`) performs 6–10 full scans of the header block. This is O(n·m).
**Fix**: build a compact index at `reset()` time into a **reused** `int[]` owned by the
`HeaderMap` (name offset, name length, value offset, value length, plus a cheap 32-bit
case-insensitive name hash per entry). Lookup becomes hash compare + one memcmp. Index arrays
grow to the connection's high-water mark and are never reallocated after warmup. Zero
allocation, strictly less work than today even for a single lookup (the scan happens once
instead of once per lookup).
**Phase**: 4.

### EX-10 — `ChunkedInputStream` performs one syscall per byte
`HttpServer.process` passes the **unbuffered** `socket.getInputStream()` (line 278) to the
parser and thence to `ChunkedInputStream`. `ChunkedInputStream.readChunkSize` (line 51),
`consumeTrailers` (line 66) and the trailing-CRLF consumption (`src.read(); src.read();` on
lines 32 and 46) all do single-byte reads. On a plain socket that is a `read(2)` syscall **per
byte** for every chunk header, every chunk terminator and every trailer line.
**Fix**: the connection read buffer must be the single source of truth for inbound bytes. Give
`ChunkedInputStream` a buffered view over the connection's read buffer (the same buffer
`RequestParser` already owns and already read-ahead into), not the raw socket stream. This also
removes the `SequenceInputStream`/`ByteArrayInputStream` wrappers.
**Phase**: 1.

### EX-11 — `WebSocketSession.readFrame` performs up to 14 syscalls per frame
`websocket/WebSocketSession.java:123-147` reads the two header bytes, the extended length (2 or
8 bytes) and the 4 mask bytes with individual `in.read()` calls on the **unbuffered** socket
stream. That is up to 14 syscalls before the payload read.
**Fix**: read the frame header into the existing `hdrScratch` array with a single bounded
`readFully`, then decode with shifts.
**Phase**: 2.

### EX-12 — WebSocket protocol gaps: no continuation frames, no mask enforcement, no length guard
`readFrame` does not handle opcode `0x0` (continuation) at all, so fragmented messages are
delivered as separate broken messages. It does not enforce that client→server frames **must**
be masked (RFC 6455 §5.1 — a server MUST close the connection on an unmasked client frame). It
does not validate the opcode. It computes `payLen` from up to 8 bytes into a `long` and only
then compares against `readBuf.length` — a 63-bit length is accepted into the comparison but
`(int) payLen` on line 149 would already have truncated if the check were reordered; today the
check is correctly placed but the negative/overflow case is untested. Control frames are not
validated for the RFC's ≤125-byte and FIN=1 requirements.
**Fix**: full RFC 6455 frame validation with named errors and correct close codes (1002
protocol error, 1009 message too big). Continuation-frame reassembly with a bounded message
size.
**Phase**: 2.

### EX-13 — `Connection` header is compared as a whole value, not as a token list
`HttpServer.isKeepAlive` (line 455) calls `request.headerEquals("Connection", "close")`, which
does an exact case-insensitive whole-value compare (`HeaderMap.valueEqualsIgnoreCase`, line
153). `Connection: keep-alive, close` therefore reads as keep-alive. The correct token-list
scan already exists three lines away in `connectionContainsUpgrade` (line 380) and is simply
not reused.
**Fix**: one shared token-list scanner used by both.
**Phase**: 2.

### EX-14 — `HEAD` responses include a body
`HttpServer.process` (lines 326-344) never special-cases `HttpMethod.HEAD`. The handler's body
is written to the socket. RFC 9110 §9.3.2: a HEAD response MUST NOT have a body (the headers,
including `Content-Length`, must match what GET would return).
**Fix**: suppress body writes for HEAD while keeping the computed `Content-Length`.
**Phase**: 2.

### EX-15 — `Content-Type` is always written, even when `ContentType.NONE`
`HttpServer.writeResponse` (lines 474-477) unconditionally writes `Content-Type: ` followed by
`response.getContentType()`. For `ContentType.NONE` (`http/ContentType.java:15`, empty byte
array) this emits the header line `Content-Type: \r\n` — a header with an empty value. Also,
`204 No Content` and `304 Not Modified` responses get `Content-Length: 0`, which RFC 9110
§8.6 forbids for 204 and discourages for 304.
**Fix**: skip `Content-Type` when the value is empty; skip `Content-Length` for 204/304 and for
1xx.
**Phase**: 2.

### EX-16 — No `Date` header
Flash never emits `Date`. RFC 9110 §6.6.1: an origin server with a clock **SHOULD** send it. It
is also the classic precompilation opportunity: format once per second on a shared daemon
thread into a pre-encoded `Date: ...\r\n` byte array, and have every response write that array.
Cost per response: one volatile read plus one `write(byte[])`.
**Fix**: `dev.relism.flash.http.DateHeader` — a single daemon thread, a `volatile byte[]`
holding the fully pre-encoded h1 field line, plus a parallel `volatile byte[]` holding the
HPACK-encoded h2 field line (Phase 9).
**Phase**: 2 (h1 form), 9 (h2 form).

### EX-17 — `HttpStatus` index array is bounded by a hand-maintained constant
`http/HttpStatus.java:53` hardcodes `MAX_STATUS_CODE = 504` and sizes `INDEX`/`REASONS` to it.
Adding any constant with a code above 504 (e.g. `507 Insufficient Storage`, `511 Network
Authentication Required`, or the h2-relevant `421 Misdirected Request`) silently throws
`ArrayIndexOutOfBoundsException` in the static initializer at class-load time.
**Fix**: compute the bound from `values()` in the static initializer. Add the status codes h2
actually needs: `421 Misdirected Request` (RFC 9110 §15.5.20, required for connection
coalescing) and `431 Request Header Fields Too Large` (needed by `EX-08`).
**Phase**: 1.

### EX-18 — `RequestParser` accepts bare LF as a line terminator in some positions
`findEndOfHeader` requires the full `\r\n\r\n`, but the per-header loop (line 147) finds `\r`
and then unconditionally advances `current = lineEnd + 2` (line 161) without verifying that
`buffer[lineEnd + 1] == '\n'`. A header line ending in a bare `\r` followed by a non-`\n`
desynchronizes the parse. Bare-LF and bare-CR handling is a known smuggling surface.
**Fix**: validate the `\n` explicitly and reject otherwise.
**Phase**: 1.

## High — allocation on the hot path

### EX-19 — `FastPathRouterImpl.route` allocates 4 objects per parametric request
`FastPathRouterImpl.java:66-80`: `new String[count]`, `new int[count]`, `new int[count]`, plus
the `PathParams` object built inside `setPathParams`. Every request matching a route with a
path parameter — i.e. most REST APIs — pays four allocations.
**Fix**: a reusable `PathParams` on the `ConnectionScratch` (`EX-06`) with pre-sized arrays
grown to the connection high-water mark, repositioned per request via a package-private
`reset(...)`. The `PathParams` lifetime contract ("valid only inside the handler") is documented
exactly like `HeaderMap`'s.
**Phase**: 4.

### EX-20 — `Response.header(String, String)` allocates 3 objects per call
`models/Response.java:134-138`: string concatenation (`StringBuilder` + `char[]` + `String`)
then `getBytes` (another `byte[]`), then possibly `new ArrayList<>()`. A response setting three
headers allocates ~10 objects. `redirect(String)` (line 129) has the same shape.
**Fix**: encode directly into the response's scratch buffer with a byte-level writer; keep the
`header(byte[] preEncoded)` overload (line 144) as the zero-cost path it already is. The
`List<byte[]> headers` field becomes a reusable growable `byte[]` region plus an `int[]` of
(offset, length) pairs.
**Phase**: 6.

### EX-21 — `Response` is allocated per request
`HttpServer.process:327` — `new Response(200, ContentType.TEXT_PLAIN)` per request.
**Fix**: a pooled, resettable `Response` on the `ConnectionScratch`. Requires `Response` to
gain a package-private `reset()`. The handler-returns-a-different-`Response` path (line 334)
must still work, so the pooled instance is used only when the handler mutates the one it was
given.
**Phase**: 6.

### EX-22 — `Request` is allocated per request, and Lombok `@Value` blocks pooling
`models/Request.java:35` is `@Value` (final class, final fields). `Request.forParsed` allocates
a `Request` **and** a `RequestBody` per request.
**Fix**: convert `Request` to a plain non-final class with a package-private `reset(...)`, and
pool it per connection (h1) / per stream slot (h2). Lombok `@Value`'s generated
`equals`/`hashCode` become meaningless under pooling and must be removed; document the change
(no user code can meaningfully depend on `Request` equality). `RequestBody` gets the same
treatment. This is the single largest API-surface-adjacent refactor in the plan and is why it
gets its own phase.
**Phase**: 6.

### EX-23 — `RequestBody.stream()` allocates 2–3 stream wrappers per call
`models/RequestBody.java:110-117` builds a `ByteArrayInputStream` and usually a
`SequenceInputStream` plus (line 130) an anonymous bounded `InputStream` with a capturing
instance.
**Fix**: one reusable `BoundedBufferedInputStream` on the `ConnectionScratch` that knows about
the pre-buffered region and the socket, repositioned per request.
**Phase**: 6.

### EX-24 — `RequestBody.drain()` allocates 8 KB per chunked request
`models/RequestBody.java:123` — `socket.transferTo(OutputStream.nullOutputStream())`. The JDK's
`transferTo` allocates a fresh `byte[8192]` on every call. `HttpServer` already keeps a
`STREAM_RELAY_BUFFER` precisely to avoid this on the write side (see its Javadoc, lines
150-156) — the read side was missed.
**Fix**: drain through the scratch relay buffer.
**Phase**: 6.

### EX-25 — `Request.path()` and `PathParams.get()` allocate twice
`Request.java:123-129` copies the view byte-by-byte into a fresh `byte[]` and then constructs a
`String` from it — two allocations and a byte-at-a-time loop. When the underlying view is
array-backed and contiguous (which it always is for h1), `new String(array, off, len, UTF_8)`
does it in one. `PathParams.get` (line 33) has the identical shape.
**Fix**: add `ByteView`-adjacent capability detection (an internal `ArrayBackedByteView`
interface exposing `array()`/`offset()`) and take the single-allocation path when available.
Keep the byte-at-a-time loop as the fallback for segmented views.
**Phase**: 4.

### EX-26 — `QueryParams.decode` always allocates, even when nothing needs decoding
`models/QueryParams.java:96-118` allocates a `byte[]` of the full length and then a `String`,
unconditionally. The overwhelmingly common case is a value containing neither `%` nor `+`.
**Fix**: scan first; if clean and array-backed, construct the `String` directly from the
backing array.
**Phase**: 4.

### EX-27 — `HttpServer.writeResponse` issues ~10 small writes per response
`HttpServer.java:469-492`: `HTTP/1.1 `, status, CRLF, `Content-Type: `, type, CRLF, custom
headers (one write each), `Content-Length: `, digits, CRLF, connection header, CRLF, body.
`BufferedOutputStream` coalesces them into one syscall, but each `write` still costs a bounds
check, a capacity check and a `System.arraycopy` with a tiny length.
**Fix**: serialize the whole response head into a reusable scratch buffer with direct index
writes, then a **single** `write(scratch, 0, len)`. This removes `BufferedOutputStream` from
the h1 response path entirely and is a prerequisite for the h2 writer discipline (Phase 3),
where holding the connection write lock across ten small writes would be unacceptable.
**Phase**: 6.

### EX-28 — `ByteTemplate.render` allocates and is O(slots²)
`template/ByteTemplate.java:52-75` allocates a `byte[][]` per render and does a nested loop over
slots for every key-value pair. Only used by `ErrorPages`, so it is off the hot path — but it
is called on every 404/500 in dev mode, and 404 is a hot path for some workloads.
**Fix**: precompute a slot-name → index map at construction; render into a reusable buffer.
Low priority, but in scope because it is exactly the "could be precompiled at boot" category.
**Phase**: 6.

### EX-29 — `Multipart` (336 lines) has not been audited
`api/multipart/Multipart.java` is the second-largest file in core and was not read during the
design pass.
**Fix**: mandatory audit against every rule in Part I: allocation profile, god-class check,
missing bounds checks on part count / part size / boundary length (multipart parsers are a
classic DoS surface), and correct behaviour when the body is streamed rather than materialized.
**Phase**: 6.

### EX-30 — `TlsConfig` cannot expose the negotiated ALPN protocol · **BLOCKER for Phase 1**
`tls/TlsConfig.java:112` can *set* `applicationProtocols`, but nothing forces the TLS handshake
before the first read, so `SSLSocket.getApplicationProtocol()` returns `null` at the point
where the protocol decision must be made. `HttpServer.process` never calls `startHandshake()`.
**Fix**: explicit `startHandshake()` on the connection's virtual thread (blocking there is free)
before protocol dispatch, with the handshake covered by `headerReadTimeoutMs`.
**Phase**: 1.

### EX-31 — TLS cipher suites are not constrained for h2
RFC 9113 §9.2.2 requires that an h2 endpoint MUST NOT use the cipher suites on the TLS 1.2
blocklist, and MUST support `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`. Flash currently leaves
suites at the JDK default (`TlsConfig.applyTo`, lines 122-131), which on some JDKs still
includes blocked suites for TLS 1.2.
**Fix**: when `h2` is among the offered ALPN protocols, filter the enabled suite list against
the RFC 9113 Appendix A blocklist. Document that TLS 1.3 is unaffected.
**Phase**: 1.

### EX-32 — `HttpServer.stop()` does not send a graceful shutdown signal
`stop()` (line 253) closes listeners and then force-closes every active socket. For h1 this
truncates in-flight responses. For h2 it skips `GOAWAY` entirely, which is a compliance failure
(RFC 9113 §6.8 — a server that closes without GOAWAY gives the client no way to know which
streams were processed).
**Fix**: two-stage shutdown — stop accepting, send `Connection: close` / `GOAWAY(last-stream-id)`,
wait up to a configurable drain timeout, then force-close. Applies to both protocols.
**Phase**: 8 (h2 GOAWAY) and 2 (h1 drain).

### EX-33 — `RequestParser.findEndOfHeader` rescans and is byte-at-a-time
`RequestParser.java:196-202` scans for `\r\n\r\n` one byte at a time; the incremental re-scan on
line 106 correctly overlaps by 3 bytes but the inner loop is still scalar.
**Fix**: SWAR scan using the same `VarHandle` `long`-read technique `fpr-core`'s `ByteCompare`
uses. Fall back to scalar for the tail. Measure — if the win is under 3 % on the h1 benchmark,
keep the scalar version and document the measurement rather than carrying complexity.
**Phase**: 4.

### EX-34 — `ServerHandle.create` hardwires the transport implementation
`ServerHandle.java:31-35` calls `new HttpServer(...)` directly. Once the transport is
decomposed (Phase 2) and a second protocol exists (Phase 3+), this factory needs to construct a
composed transport rather than a god object.
**Fix**: keep `ServerHandle` as the public contract; move construction behind a
package-private `TransportFactory`.
**Phase**: 2.

### EX-35 — `Transfer-Encoding` multi-value handling drops the message boundary silently
Found while implementing `EX-02` in `RequestParser.java`'s header-scan loop (the exact code that
decides `isChunked`). The pre-existing check was `equalsIgnoreCase(buffer, valueStart, lineEnd,
"chunked")` — an exact **whole-value** comparison. RFC 9112 §6.1 requires only that `chunked` be
the **final** coding in a comma-separated list (e.g. `Transfer-Encoding: gzip, chunked` is valid
and self-delimiting). The old check silently treated any such multi-coding value as *not*
chunked at all — `isChunked` stayed `false`, `contentLength` stayed `0`, and the body bytes that
followed were left for the next `parse()` call to misinterpret as the start of a new request:
a real message-boundary corruption, not just a missed feature.
**Fix**: parse the comma-separated token list and inspect only the last token
(`RequestParser.isFinalCodingChunked`). A value whose final coding is not `chunked` is now
rejected with `501` (`EX-02`'s own fix), rather than silently misparsed.
**Phase**: 1.

### EX-36 — A header line without a `:` was silently skipped instead of rejected
Found in the same loop as `EX-18`/`EX-35`. `RequestParser`'s header-line loop located the colon
via `find(...)` and, if none was found (`colon == -1`), simply did nothing for that line and
moved on to the next — a malformed header line was permissively ignored rather than rejected.
RFC 9112 §5 gives no such leniency: a header field line without a colon is not valid HTTP.
**Fix**: `colon == -1` now rejects the request with `400 Bad Request`.
**Phase**: 1.

### EX-37 — `BufferedByteSource`'s deadline mechanism NPEs against a `null` socket, so it was never actually testable in isolation
Found while writing `Http2FrameReaderTest` (Phase 5): `BufferedByteSource.clearDeadline()` and
`fillFromUnderlying()` both call `socket.setSoTimeout(...)` unconditionally. Every isolated unit
test in this codebase that constructs a `BufferedByteSource` directly (over a
`ByteArrayInputStream`, to test a parser/reader without a real connection) passes `null` for
`socket` — the codebase's own established idiom, used throughout `RequestParserTest`,
`ChunkedInputStreamTest`, `RequestParserSecurityTest`. That idiom works today only because none
of those tests ever call `setDeadline`/trigger a deadline-bounded read — `RequestParser` itself
never calls `setDeadline` (only `Http1Connection`, which always has a real socket, does). The
moment any code under test (here, `Http2FrameReader`, which correctly uses the deadline exactly
as `EX-07` designed it) sets a deadline and then performs a read against a `null`-socket source,
both methods threw `NullPointerException` instead of the intended `SocketTimeoutException`/
normal read. `BufferedByteSource` — the class that exists specifically to implement `EX-07`'s
slowloris defence — had **zero** dedicated unit tests (`BufferedByteSourceTest` did not exist);
its deadline mechanism was exercised only indirectly, end-to-end, via real-socket tests
(`HttpServerTimeoutTest`), which never hit this path.
**Fix**: both methods now skip the `socket.setSoTimeout(...)` call when `socket == null` — a
`null` socket means "no OS-level timeout to bound", not a misuse; the deadline-expiry check
itself (`remainingNanos <= 0` → `SocketTimeoutException`) is independent of the socket and keeps
working. Production always supplies a real socket, so no production behavior changes.
`BufferedByteSourceTest.java` added (previously absent) with direct coverage of the deadline
mechanism against a `null` socket, closing the actual test gap this bug lived in.
**Phase**: 5 (found and fixed while building `Http2FrameReaderTest`).

### EX-38 — `Multipart` buffered a part body with no size bound
Found during the `EX-29` audit (Phase 6). `Multipart.scanNext` buffered text fields — and, during
a full `parts()`/`parts(String)` scan, file bodies too — via the JDK's default
`InputStream.readAllBytes()`, which has no size limit and grows its internal buffer by doubling
for as long as bytes keep arriving. `Http1Limits.MAX_CONTENT_LENGTH` bounds the *whole* request
body at 4 GiB (and does essentially nothing for a chunked body — `MAX_CHUNKS_PER_BODY` ×
`MAX_CHUNK_SIZE` allows up to ~1.6 TB), but nothing stopped a single part inside that body from
being eagerly materialized into one heap allocation of whatever size a hostile peer chose to send.
**Fix**: `readBoundedBody` replaces the `readAllBytes()` call, throwing `IOException` once the
part exceeds `Http1Limits.MAX_MULTIPART_BUFFERED_PART_SIZE` (10 MiB). Deliberately does **not**
apply to `Part.materialize()` on a streaming file part returned by `Multipart.file()` — that call
is documented as an explicit, opt-in heap allocation the caller chooses to pay for.
**Phase**: 6.

### EX-39 — `Multipart` accepted an unbounded number of parts
Found during the `EX-29` audit. `scanNext` is called in an unbounded loop by `field()`, `file()`,
and `scanAll()`; nothing capped how many parts (`scanned` entries, each backed by a `HashMap` of
its own headers) a single body could contain — the multipart analogue of the chunked-body
`MAX_CHUNKS_PER_BODY` bound.
**Fix**: a `partCount` counter checked against the new `Http1Limits.MAX_MULTIPART_PARTS` (1,000)
at the top of every `scanNext` call.
**Phase**: 6.

### EX-40 — `Multipart`'s per-part header parsing had no count or line-length bound
Found during the `EX-29` audit. `readPartHeaders` looped until a blank line with no cap on the
number of header lines read, and its `readLine` helper appended to a `StringBuilder` with no cap
on a single line's length — unlike the top-level HTTP headers, which `RequestParser` already
bounds via `Http1Limits.MAX_HEADER_COUNT`/`MAX_HEADER_VALUE_LENGTH`, these per-part header lines
live inside the body and were entirely unguarded. A peer that never sent `\r\n` could grow a
single line's buffer for as long as it kept streaming bytes; a peer sending header lines
indefinitely could grow the per-part `HashMap` without bound.
**Fix**: `readPartHeaders` now rejects a part once it exceeds
`Http1Limits.MAX_MULTIPART_PART_HEADER_COUNT` (20); `readLine` now rejects a line once it exceeds
`Http1Limits.MAX_MULTIPART_HEADER_LINE_LENGTH` (8,192 bytes) — both throw `IOException`.
**Phase**: 6.

### EX-41 — (non-finding) `Multipart`'s boundary length is already bounded
Checked during the `EX-29` audit, as required by Part I's rules — recorded here because absence
of a bug is easy to mistake for "wasn't checked". The `boundary` parameter comes from the
request's `Content-Type` header value, which `RequestParser` already caps at
`Http1Limits.MAX_HEADER_VALUE_LENGTH` (8,192 bytes) before `Multipart.of` ever sees it — no
separate bound needed in `Multipart` itself.
**Phase**: 6.

### EX-42 — `RequestParser.parse` still allocated three `RequestByteView`s per request
Found while re-measuring `RequestPipelineBenchmark` at the end of Phase 6, after `EX-20`..`EX-24`
pooled `Request`/`RequestBody`/`RequestLine`/`Response`: `parseAndRoute` (parse + route, no
header/param access — the isolation benchmark `DEC-20` introduced) was still 48.008 B/op, not the
0 B/op Phase 6's own zero-alloc contract requires. `RequestParser.parse` built a fresh
`FastPathViews.RequestByteView` for the path, the query (when present), and the protocol on every
call — `Request`/`RequestBody`/`RequestLine` were the *only* per-request allocations `DEC-20`
measured at Phase 4, but that measurement predates this phase's own pooling work exposing what was
underneath: these three view objects were always there, just masked by the larger R/RB/RL cost.
**Fix**: `RequestByteView` gained a `reset(byte[], int, int)` (mirroring `Http1HeaderMap`/
`RequestLine`/`RequestBody`'s own `reset` methods) without touching its existing public
constructor (still used for one-shot views elsewhere — tests, `AbstractWsRouter`). `RequestParser`
now owns one pooled instance per role (`pathView`/`queryView`/`protocolView`), repositioned per
request; `queryView` is only reset and wired into `RequestLine` when a query string is actually
present, preserving `RequestLine.getQuery()`'s existing "`null` means no query" contract.
**Result**: `parseAndRoute` measured 0.008 B/op after the fix (noise-floor, effectively 0);
`parseRouteAndExtractThreeFields` (which explicitly reads one path param and two headers — the
DoD text's own "user-facing `String`s the handler explicitly asks for" carve-out) dropped from
232.009 to 184.009 B/op, the same 48 bytes accounted for exactly.
**Phase**: 6.

### EX-43 — `Response.header(...)` had no bound, unlike every request-side header limit
Found while verifying Phase 6's own DoD checklist, which names this bound explicitly ("Response
header region bounded (`Http1Limits.MAX_RESPONSE_HEADER_BYTES`) — a handler in a loop calling
`header(...)` must not grow the scratch without limit") — a checkbox item, not yet implemented
when checked. `Response.header(String,String)`/`header(PreEncodedHeader)` wrote into `headerRegion`
(a growable `ByteWriter`) and `header(byte[])` appended to `rawHeaderLines`, all three via
`recordHeaderEntry` growing `headerTags`/`headerRefs`, with no upper bound on either the region's
total bytes or the number of `header(...)` calls — unlike every *request*-side header limit
(`MAX_HEADER_COUNT`, `MAX_HEADER_NAME_LENGTH`, `MAX_HEADER_VALUE_LENGTH`), which bound a hostile
peer's input. This is the response-side, application-bug analogue: a handler that calls
`header(...)` in an unbounded loop (e.g. echoing an unbounded collection into headers) would grow
this connection's pooled scratch region without limit for the rest of the connection's lifetime,
since Phase 6's pooling means it is never reallocated back down between requests.
**Fix**: two new limits, `Http1Limits.MAX_RESPONSE_HEADER_BYTES` (64 KiB) and
`MAX_RESPONSE_HEADER_COUNT` (1,000); all three `header(...)` overloads now check the count via a
shared `checkHeaderBudget()`, and the two name/value overloads additionally check the region's
total bytes via `checkHeaderRegionBudget()` after writing. Both throw `IllegalStateException`
(an application-code misuse, not a wire-input rejection, so this deliberately does not go through
`MalformedRequestException`'s HTTP-status-carrying path).
**Phase**: 6.

### EX-44 — Comment cleanup removed `Multipart.partCount` from compiled source
Found during the Phase 7 clean build. The process-reference cleanup commit removed the complete
field declaration because its trailing comment contained an `EX-nn` marker. Incremental builds
initially reused the previously compiled class and hid the source-level failure. **Fix**: restored
the counter without the process comment and audited every non-comment line removed by the cleanup
commit. `MultipartTest`'s part-count limit coverage remains the regression test; phase closure now
uses `mvn clean test` so stale classes cannot mask source damage. **Phase**: 7.

### EX-45 — Stateful HTTP/2 protocol instance was shared across accepted sockets
Found by running h2spec repeatedly against the Phase 8 transport integration. `TransportFactory`
constructed one `Http2Connection` and `ConnectionRunner` reused it for every accepted socket, which
is valid for the stateless `Http1Connection` but leaked SETTINGS, GOAWAY and flow-control state
between HTTP/2 peers. **Fix**: `ConnectionRunner` now receives an HTTP/2 protocol factory and creates
one state machine per accepted HTTP/2 connection. `Http2ConnectionIntegrationTest` first poisons one
connection with a protocol error, then verifies that a second connection completes a fresh SETTINGS
exchange and PING/PONG. **Phase**: 8.

### EX-46 — Date header was not IMF-fixdate compliant on days 1–9

Found while precompiling the HTTP/2 Date field. `DateHeader` used Java's
`DateTimeFormatter.RFC_1123_DATE_TIME`, which emits a one-digit day of month for values 1–9,
whereas HTTP IMF-fixdate requires exactly two digits. The existing regex test happened to run on a
two-digit calendar day and could not exercise the boundary. **Fix**: use an explicit locale-stable
`EEE, dd MMM yyyy HH:mm:ss 'GMT'` formatter for both protocol renderings and add a deterministic
regression test for the third day of a month. **Phase**: 9.

### EX-47 — A reset queued stream could be returned to the pool before dispatch observed it

Found while closing the stream-dispatch cancellation paths. `receiveRstStream` transitioned a
queued stream to `CLOSED` before deciding whether its release had to be deferred. The subsequent
state check could therefore no longer see `HALF_CLOSED_REMOTE`, returned the object to the pool,
and left the same object referenced by the dispatch queue. A following request could acquire and
mutate it before the queue drained. **Fix**: capture the deferred-release condition before the
transition, mark queued/dispatched streams cancelled, and let the sole queue/worker owner perform
the final release. The regression test sends a complete request, immediately resets it, then sends
a second request and proves that only the second handler invocation and response occur. **Phase**:
10.

---

# PART III — The phases

```
Phase 0   Groundwork: package layout, limits, error model, style contract
Phase 1   HTTP/1.1 hardening + ALPN/preface plumbing   ← safety debt paid before new code
Phase 2   Transport decomposition (kill the HttpServer god class)
Phase 3   The serialized frame writer + JMH gate       ← THE GO/NO-GO GATE
Phase 4   Byte-layer foundations: views, scanning, header index, scratch
Phase 5   Frame layer: reader, writer wiring, frame validation
Phase 6   Request/Response model refactor (pooling, protocol neutrality)
Phase 7   HPACK decoder (Huffman, static, dynamic, arena)
Phase 8   Connection state machine: SETTINGS, PING, GOAWAY, WINDOW_UPDATE
Phase 9   HPACK encoder + boot-time precompilation + h2 response path
Phase 10  Stream state machine + dispatch + h2 Request assembly
Phase 11  DATA, flow control, request/response bodies, streaming
Phase 12  Trailers, half-close, gRPC end-to-end
Phase 13  Security hardening & abuse resistance
Phase 14  h2c prior knowledge + upstream/proxy support
Phase 15  RFC 8441 extended CONNECT (WebSocket over HTTP/2)
Phase 16  Compliance test suite
Phase 17  Benchmarks, allocation gates, performance tuning
Phase 18  Documentation
```

Phases 0–2 touch **only existing code** and ship value on their own even if h2 were abandoned.
Phase 3 is the go/no-go gate. Phases 4–6 are shared foundations. Phases 7–15 are h2 proper.

---

## Phase 0 — Groundwork

**Goal.** Establish the package layout, the limits/error model, and the written style contract
so that no later phase has to invent conventions.

**Why now.** Every later phase references these constants and this layout. Doing it first
prevents three different naming schemes for the same idea.

### Files created

```
flash/src/main/java/dev/relism/flash/http2/Http2Limits.java
flash/src/main/java/dev/relism/flash/http2/Http2ErrorCode.java
flash/src/main/java/dev/relism/flash/http2/Http2Exception.java
flash/src/main/java/dev/relism/flash/http2/Http2StreamException.java
flash/src/main/java/dev/relism/flash/http/Http1Limits.java
flash/docs/http2/IMPLEMENTATION-PLAN.md          (this file)
flash/docs/http2/DECISIONS.md                    (decision log, see below)
```

### Package layout (final; later phases fill it in)

```
dev.relism.flash.http2
├── Http2Limits.java               every bound, every default, each with its attack rationale
├── Http2ErrorCode.java            the 14 RFC 9113 §7 codes, with pre-encoded 4-byte forms
├── Http2Exception.java            connection error → GOAWAY
├── Http2StreamException.java      stream error → RST_STREAM
├── Http2Settings.java             the 6 SETTINGS params, local + remote, with validation
├── Http2Connection.java           the demux loop and connection-level state. ONE responsibility.
├── Http2ConnectionScratch.java    all per-connection reusable buffers (extends the shared one)
├── frame/
│   ├── FrameType.java             typed constants + per-type size/flag validation rules
│   ├── FrameFlags.java            bitwise flag constants and predicates
│   ├── FrameHeader.java           a *flyweight* over the read buffer — never allocated per frame
│   ├── Http2FrameReader.java      read 9 bytes + payload into the connection buffer
│   ├── Http2FrameWriter.java      the serialized writer (Phase 3) — the only thing that writes
│   └── FrameValidator.java        RFC-mandated per-type checks, table-driven
├── hpack/
│   ├── HpackStaticTable.java      61 entries, precompiled byte[][] + name→index lookup
│   ├── HpackDynamicTable.java     ring buffer of (nameOff,nameLen,valOff,valLen) + arena
│   ├── HpackDecoder.java          all 6 representations, integer prefix decoding
│   ├── HpackEncoder.java          static-table-only encoder (see DEC-04)
│   ├── Huffman.java               decode FSM tables + encode LUT, both built at class-init
│   └── HpackIntegers.java         prefix-coded integer read/write, overflow-safe
├── stream/
│   ├── Http2Stream.java           per-stream state; also the intrusive MPSC queue node
│   ├── Http2StreamState.java      the RFC 9113 §5.1 state machine as an explicit table
│   ├── Http2StreamTable.java      int→stream, open-addressed, zero-alloc
│   └── Http2FlowController.java   the two-level window accounting
├── message/
│   ├── Http2HeaderMap.java        HeaderMap implementation backed by HPACK output
│   ├── Http2RequestBody.java      DATA frames → bounded InputStream
│   └── PseudoHeaders.java         :method/:scheme/:authority/:path/:protocol/:status handling
└── upgrade/
    ├── Http2PrefaceDetector.java  h2c prior-knowledge detection (Phase 14)
    └── ExtendedConnect.java       RFC 8441 (Phase 15)
```

And, in existing packages:

```
dev.relism.flash.transport            (new, Phase 2)
├── BoundListener.java
├── ListenerBinder.java
├── AcceptLoop.java
├── ConnectionRunner.java
├── ConnectionProtocol.java           the h1/h2 seam
├── ConnectionScratch.java            EX-06 fix
├── ScratchPool.java
├── ProtocolNegotiator.java           ALPN + preface (Phase 1)
└── ServerLifecycle.java

dev.relism.flash.http1                (new, Phase 2 — moved out of the god class)
├── Http1Connection.java
├── Http1ResponseWriter.java
├── Http1ChunkedEncoder.java
└── Http1KeepAlive.java

dev.relism.flash.bytes                (new, Phase 4 — protocol-neutral byte utilities)
├── ByteScan.java                     SWAR + scalar scanning, token lists, case-insensitive cmp
├── ArrayBackedByteView.java          capability interface (array/offset) — enables EX-25
├── SegmentedByteView.java            multi-segment view (supportsLong() == false)
├── PooledSlice.java                  reusable slice, fixes EX-05
├── ByteWriter.java                   index-based writes into a growable scratch buffer
└── Pairs.java                        the (hi<<32)|lo idiom, named and documented
```

### Tasks

1. Create `flash/docs/http2/DECISIONS.md` seeded with the decisions already made in this plan
   (`DEC-01` … `DEC-08`, listed in Part VI). Every subsequent non-obvious choice appends an
   entry: context, options, decision, consequence. This is how the next agent understands why
   the encoder has no dynamic table.
2. Write `Http2ErrorCode` as an enum of the 14 RFC 9113 §7 codes with `code()` and a
   **pre-encoded 4-byte big-endian `byte[]`** per constant (used in RST_STREAM and GOAWAY
   payloads without formatting).
4. Write `Http2Limits` with every bound this plan will need. Each field gets a Javadoc naming
   the attack or resource it bounds and, where applicable, the CVE. Initial contents:
   `MAX_CONCURRENT_STREAMS` (100), `MAX_FRAME_SIZE_LOCAL` (16384 initially; tunable),
   `MAX_HEADER_LIST_SIZE` (32768), `MAX_CONTINUATION_FRAMES_PER_BLOCK` (8, CVE-2024-27316),
   `MAX_RESET_STREAMS_PER_INTERVAL` + `RESET_RATE_INTERVAL_MS` (CVE-2023-44487),
   `MAX_SETTINGS_ENTRIES_PER_FRAME`, `MAX_PING_QUEUE_DEPTH`,
   `MAX_STREAMS_CREATED_PER_INTERVAL`, `MAX_EMPTY_DATA_FRAMES_PER_STREAM`,
   `INITIAL_WINDOW_SIZE_LOCAL`, `CONNECTION_WINDOW_SIZE_LOCAL`,
   `HPACK_DYNAMIC_TABLE_SIZE_LOCAL` (4096), `MAX_HPACK_STRING_LENGTH`,
   `HEADER_BLOCK_ASSEMBLY_TIMEOUT_MS`, `STREAM_IDLE_TIMEOUT_MS`.
5. Write `Http1Limits` with the h1 bounds required by `EX-03`, `EX-07`, `EX-08`.
6. Define the exception model:
   - `Http2Exception` — a **connection** error. Carries an `Http2ErrorCode` and a debug string.
     Terminates the connection with GOAWAY. Preallocated singletons for the common codes so the
     error path itself does not allocate (with stack traces disabled via the
     `(msg, cause, suppression, writableStackTrace)` constructor — document why).
   - `Http2StreamException` — a **stream** error. Carries code + stream id. Results in
     RST_STREAM; the connection survives.
   - Neither extends `IOException`; both are caught explicitly by the connection loop, so a
     protocol error is never confused with a socket error.
7. Add `h2` to the allowed commit scopes in `AGENTS.md:39-41` (a `docs:` commit), or record in
   `DECISIONS.md` that `core` is used instead.

### Zero-alloc contract
Constants only; nothing runs at request time in this phase.

### Tests
`Http2ErrorCodeTest` (round-trip code ↔ pre-encoded bytes), `Http2LimitsTest` (every limit is
positive and internally consistent, e.g. `MAX_FRAME_SIZE_LOCAL` within RFC bounds
16384..16777215).

### Docs
`flash/docs/http2/DECISIONS.md` created.

### DoD
- [x] Package skeleton compiles (empty classes are acceptable only for classes whose phase has
      not arrived; every class listed above that belongs to Phase 0 is complete). Verified:
      `mvn -pl flash -am test` — full module, 226/226 tests green, including the new
      `Http2ErrorCodeTest`, `Http2LimitsTest`, `Http2ExceptionTest`, `Http2StreamExceptionTest`,
      `Http1LimitsTest` (19 tests).
- [x] `DECISIONS.md` seeded with `DEC-01` … `DEC-08` (seeded with `DEC-01`…`DEC-11`: the extra
      `DEC-11` records the AGENTS.md commit-scope choice from task 7 below).
- [x] `Http2Limits` and `Http1Limits` complete, every field documented with its rationale.
- [x] No `TODO` comments anywhere. (This applies to every phase.) Verified by grep.

---

## Phase 1 — HTTP/1.1 hardening and protocol-negotiation plumbing

**Goal.** Fix the security and correctness debt in the existing HTTP/1.1 parser, and make the
server able to decide "this connection is h1 or h2" without yet being able to speak h2.

**Why now.** Two reasons. First, `EX-02`, `EX-03`, `EX-07`, `EX-08` and `EX-18` are live
vulnerabilities in shipped code and must not wait behind a large feature. Second, `EX-30` (ALPN
is unreadable) blocks every h2 phase, and fixing it is the natural companion to the negotiation
seam.

### EX items
`EX-02`, `EX-03`, `EX-07`, `EX-08`, `EX-10`, `EX-17`, `EX-18`, `EX-30`, `EX-31`, plus two found
while implementing this phase and registered in Part II per R10: `EX-35` (multi-value
`Transfer-Encoding` silently misparsed), `EX-36` (a header line with no `:` silently skipped).

### Files

Modified:
- `flash/src/main/java/dev/relism/flash/RequestParser.java`
- `flash/src/main/java/dev/relism/flash/ChunkedInputStream.java`
- `flash/src/main/java/dev/relism/flash/HttpServer.java`
- `flash/src/main/java/dev/relism/flash/http/HttpStatus.java`
- `flash/src/main/java/dev/relism/flash/tls/TlsConfig.java`
- `flash/src/main/java/dev/relism/flash/extension/FlashConfiguration.java`

Created:
- `flash/src/main/java/dev/relism/flash/http/Http1Limits.java` (from Phase 0; extended here with
  the chunked-transfer bounds for `EX-10`'s safety task)
- `flash/src/main/java/dev/relism/flash/transport/ProtocolNegotiator.java`
- `flash/src/main/java/dev/relism/flash/transport/NegotiatedProtocol.java` (enum: `HTTP_1_1`, `H2`)
- `flash/src/main/java/dev/relism/flash/transport/BufferedByteSource.java` — **plan correction**:
  task 8 below requires this class (the buffered, deadline-aware, peekable source `EX-10`'s fix
  and `EX-07`'s absolute-deadline requirement both need), but it was missing from this phase's
  original Files list. Added here; recorded as `DEC-12` in `DECISIONS.md`.
- `flash/src/main/java/dev/relism/flash/exceptions/MalformedRequestException.java` — likewise
  not originally listed: the typed, status-carrying rejection `EX-02`/`EX-03`/`EX-08`/`EX-18`
  all need to tell `HttpServer` which status to respond with, as distinct from
  `HttpException` (which routes through the user's handler chain — a malformed request must
  not). Recorded alongside `DEC-12`.

### Tasks

1. **Strict `Content-Length` parsing** (`EX-03`). Replace `RequestParser.parseLong` with a
   strict parser: empty → reject; any byte outside `'0'..'9'` → reject; more than 19 digits →
   reject; value > `Http1Limits.MAX_CONTENT_LENGTH` → reject with `413`. Return `-1` as the
   "invalid" sentinel and raise a typed `HttpException` mapped to `400`.
2. **Reject `Content-Length` + `Transfer-Encoding`** (`EX-02`). Track both as booleans during
   the header scan. Both present → `400`, connection closed (never keep-alive: a smuggling
   attempt must not leave a reusable connection). Multiple `Content-Length` lines with
   different values → `400`. `Transfer-Encoding` whose last coding is not `chunked` → `501`.
3. **Reject bare CR/LF desync** (`EX-18`). After locating `\r` at `lineEnd`, assert
   `buffer[lineEnd + 1] == '\n'` before advancing; otherwise `400`. Also reject a header line
   that begins with whitespace (obs-fold, deprecated by RFC 9112 §5.2 and a smuggling vector)
   with `400`.
4. **Header count and size limits** (`EX-08`). Count headers during the scan; enforce
   `MAX_HEADER_COUNT`, `MAX_HEADER_NAME_LENGTH`, `MAX_HEADER_VALUE_LENGTH`. Enforce
   `MAX_REQUEST_LINE_LENGTH` against `headerEndIdx - base` for the request line specifically.
   Over-limit → `431 Request Header Fields Too Large` (added in task 6).
5. **Header name charset validation.** Reject any header name byte outside the RFC 9110 `tchar`
   set. Currently a name containing a space or a control character is accepted. Table-driven:
   a `boolean[256]` (or a 4-`long` bitmap for cache friendliness) built at class-init — a
   precompilation opportunity per R4.
6. **`HttpStatus` bound fix and additions** (`EX-17`). Compute `MAX_STATUS_CODE` from
   `values()`. Add `MISDIRECTED_REQUEST(421)`, `REQUEST_HEADER_FIELDS_TOO_LARGE(431)`,
   `EXPECTATION_FAILED(417)`, `PRECONDITION_FAILED(412)`, `RANGE_NOT_SATISFIABLE(416)`,
   `INSUFFICIENT_STORAGE(507)`, `NETWORK_AUTHENTICATION_REQUIRED(511)`, and
   `HTTP_VERSION_NOT_SUPPORTED(505)`.
7. **Timeouts** (`EX-07`). Add `headerReadTimeoutMs`, `idleKeepAliveTimeoutMs`,
   `bodyReadTimeoutMs`, and `shutdownDrainTimeoutMs` to `FlashConfiguration` with defaults
   10 000 / 60 000 / 30 000 / 15 000. Apply via `Socket.setSoTimeout` around the appropriate
   read phases, switching the value as the connection moves between idle-wait, header-read and
   body-read. Document that `setSoTimeout` is per-read, so a slowloris sending one byte per
   9 seconds needs the additional absolute deadline check on the header loop — implement that
   deadline, do not rely on `setSoTimeout` alone.
8. **Buffered chunked reads** (`EX-10`). `ChunkedInputStream` must read through the connection's
   buffered source, not the raw socket stream. Concretely: introduce a
   `BufferedByteSource` owned by the connection that wraps the read buffer plus the socket and
   exposes `readByte()`, `readFully(byte[],int,int)`, `skip(long)` and `peek()` without
   syscalls per byte. `RequestParser` and `ChunkedInputStream` both consume it. This also
   removes the `SequenceInputStream`/`ByteArrayInputStream` construction in
   `ChunkedInputStream`'s constructor.
9. **Chunk-size safety.** `readChunkSize` must reject: more than 16 hex digits, a size above
   `Http1Limits.MAX_CHUNK_SIZE`, a chunk-extension longer than `MAX_CHUNK_EXT_LENGTH`, and more
   than `MAX_CHUNKS_PER_BODY` chunks (a "many zero-length chunks" DoS). Trailer section bounded
   by `MAX_TRAILER_COUNT` and `MAX_HEADER_VALUE_LENGTH`.
10. **ALPN readability** (`EX-30`). In the connection runner, if the socket is an `SSLSocket`,
    call `startHandshake()` explicitly (under `headerReadTimeoutMs`) before protocol dispatch.
    Add `TlsConfig.negotiatesH2()` so the negotiator knows whether to even look.
11. **h2 cipher constraints** (`EX-31`). When `applicationProtocols` contains `h2`, filter
    enabled cipher suites against the RFC 9113 Appendix A blocklist in `TlsConfig.applyTo`.
    The blocklist is a `Set<String>` built once in a static initializer. Document that TLS 1.3
    suites are unaffected and that this only narrows TLS 1.2.
12. **`ProtocolNegotiator`**. A single class with one method:
    `NegotiatedProtocol negotiate(Socket socket, BufferedByteSource source)`.
    Logic, in order:
    - If `SSLSocket` and `getApplicationProtocol()` equals `"h2"` → `H2`.
    - If `SSLSocket` and it equals `"http/1.1"` or is null/empty → `HTTP_1_1`.
    - If plain and the first 24 bytes peeked from `source` equal the client connection preface
      `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n` → `H2` (h2c prior knowledge; wired up in Phase 14, but
      the detection lives here from the start so there is one place that decides).
    - Otherwise → `HTTP_1_1`.
    The peek must not consume: `BufferedByteSource.peek(int n)` fills the buffer without
    advancing the read position. This is why the buffered source (task 8) comes first.
    Note for the implementer: today an h2c prior-knowledge client gets
    `"Unsupported HTTP method"` from `HttpMethod.fromBytes`, because the `'P'` branch
    (`http/HttpMethod.java:26-32`) tests for `PUT`/`POST`/`PATCH`/`PURGE` and `PRI` matches
    none. Confirm this is no longer reachable after the negotiator lands.
13. In this phase the negotiator's `H2` result leads to a clean rejection, not an h2 session:
    for TLS, respond by closing after sending nothing (the client will retry h1 per ALPN
    semantics only if we did not select h2 — so **do not offer `h2` in ALPN yet**; the
    negotiator is exercised only by tests until Phase 8). For plain h2c preface, close.
    Add a `FlashConfiguration.http2Enabled` flag, default `false`, which gates both offering
    `h2` in ALPN and accepting the h2c preface. It flips to `true` in Phase 12's DoD.

### Zero-alloc contract
- The strict `Content-Length` parser, the token/charset validators and the limit checks must
  allocate nothing. No `String` is constructed for validation.
- `BufferedByteSource` allocates its buffer once per connection.
- Error paths may allocate (they terminate the connection), but the pre-encoded error response
  bodies must come from `AbstractRouter`'s existing precompiled constants where a status
  already has one.

### Safety checks (checklist — all mandatory)
- [x] `Content-Length` strict-numeric, bounded, single-valued — `RequestParserSecurityTest`
- [x] `Content-Length` + `Transfer-Encoding` rejected, regardless of order — `RequestParserSecurityTest`
- [x] Non-`chunked` final transfer coding rejected — `RequestParserSecurityTest`
- [x] Bare CR / missing LF rejected — `RequestParserSecurityTest`
- [x] obs-fold (leading whitespace continuation line) rejected — `RequestParserSecurityTest`
- [x] Header name `tchar` validated — `RequestParserSecurityTest`
- [x] Header count / name length / value length / request-line length bounded — `RequestParserSecurityTest`
- [x] Chunk size, chunk count, chunk-extension length, trailer count bounded — `ChunkedInputStreamTest`
- [x] Header-read absolute deadline enforced (not just `setSoTimeout`) — `HttpServerTimeoutTest.slowlorisHeaderDribble_disconnectedWithinHeaderReadTimeout`
- [x] Idle keep-alive timeout enforced — `HttpServerTimeoutTest.idleKeepAliveConnection_disconnectedWithinIdleTimeout`
- [x] Body-read timeout enforced — `HttpServerTimeoutTest.slowBodyDribble_disconnectedWithinBodyReadTimeout`
- [x] TLS handshake covered by a timeout — `HttpServerTimeoutTest.tlsHandshakeNeverStarted_disconnectedWithinHeaderReadTimeout`

### Tests
- `RequestParserSecurityTest` — one test per rejection above, each asserting both the status
  code and that the connection is closed (not kept alive).
- `RequestParserTest` — existing tests must still pass unmodified except where they encoded the
  buggy behaviour; any such change is called out in the PR description with justification.
- `ChunkedInputStreamTest` — extended with malformed-input cases and a syscall-count assertion
  (via a counting `InputStream` wrapper) proving the per-byte syscalls are gone.
- `HttpServerTimeoutTest` — slowloris simulation: a client that dribbles bytes must be
  disconnected within `headerReadTimeoutMs` ± tolerance.
- `ProtocolNegotiatorTest` — ALPN `h2`, ALPN `http/1.1`, ALPN absent, h2c preface, partial
  preface, preface-lookalike (`PRI ` followed by garbage), plain `GET`.
- `TlsConfigTest` — extended for cipher filtering when `h2` is offered.

### Docs
- `README.md`: new `FlashConfiguration` timeout fields documented in the config table
  (lines 161-170).
- New `flash/docs/http2/HTTP1-HARDENING.md` listing every rejection rule and its RFC citation, so
  operators can understand a `400` in their logs.

### DoD
- [x] Every checklist item above is implemented and tested.
- [x] `mvn test` green. Full `flash` module: 277/277, run twice in a row for timing-test stability
      (the four `HttpServerTimeoutTest` cases are wall-clock-based).
- [x] No behavioural change to well-formed HTTP/1.1 traffic (verified by the existing test
      suite passing unmodified — the only test-file edits were signature updates for
      `RequestParser.parse(BufferedByteSource)` and exception-type/status updates for the small
      number of existing tests that asserted the pre-fix buggy behaviour, e.g. a 5 GB
      `Content-Length` being silently accepted, or an unrecognised method producing a bare
      `IOException` instead of a typed `501`; each such change is called out in the Phase 1
      commit).
- [ ] h1 benchmark shows no regression beyond noise (baseline captured before the phase). **Not
      verified — no JMH harness exists yet; it is a Phase 3 deliverable.** Left unchecked
      rather than claimed. Once Phase 3 adds the harness, an h1 GET benchmark should be run
      against the pre-Phase-1 commit and against this one before Phase 3 is considered started,
      so this box can be resolved retroactively.

---

## Phase 2 — Transport decomposition

**Goal.** Break `HttpServer` (563 lines, eleven responsibilities) into named, single-purpose
components, introduce the per-connection scratch object, and create the seam where a second
protocol will plug in — without changing any observable behaviour.

**Why now.** Phase 3's writer needs a connection-scoped home. Phases 10+ need a place to hang
an h2 connection that is not "inside a 563-line class that also does WebSocket handshakes".
And `EX-06` (`ThreadLocal` on virtual threads) is a production memory hazard that the h2 work
would multiply.

### EX items
`EX-01`, `EX-06`, `EX-11`, `EX-12`, `EX-13`, `EX-14`, `EX-15`, `EX-16`, `EX-32`, `EX-34`.

### Files

Created — `dev.relism.flash.transport`:
- `BoundListener.java` — the record currently nested in `HttpServer` (line 108), promoted.
- `ListenerBinder.java` — `HttpServer.bind` (lines 193-208), extracted. Sole responsibility:
  turn a `FlashConfiguration.Listener` into a bound `ServerSocket`.
- `AcceptLoop.java` — `HttpServer.acceptLoop` (lines 238-250) plus the accept-thread spawning
  from `start()` (lines 213-223).
- `ConnectionRunner.java` — the body of `HttpServer.process` (lines 273-369) minus everything
  protocol-specific. Sole responsibility: own the socket lifecycle, configure socket options,
  acquire a `ConnectionScratch`, run the negotiator, hand off to a `ConnectionProtocol`,
  guarantee cleanup.
- `ConnectionProtocol.java` — the seam:
  ```java
  interface ConnectionProtocol {
      /** Runs this connection to completion. Returns when the connection should be closed. */
      void run(ConnectionContext ctx) throws IOException;
  }
  ```
- `ConnectionContext.java` — socket, streams, remote address, `SSLSocket` or null,
  `BufferedByteSource`, `ConnectionScratch`, the routers, the configuration, a `stopped`
  supplier. One object passed down instead of eight parameters.
- `ConnectionScratch.java` — **the `EX-06` fix.** Owns: decimal-format buffer (20 B), relay
  buffer (8 KB), `MessageDigest` for the WS handshake, router `MatchResult`,
  `MethodPathByteView`, reusable `PathParams`, reusable `Response`, reusable `Request`,
  reusable `RequestBody`, the response head scratch buffer, and (from Phase 3) the h2 write
  scratch. Allocated once per connection, returned to `ScratchPool` on close.
- `ScratchPool.java` — a bounded pool (`ConcurrentLinkedQueue` + an `AtomicInteger` size guard,
  or a striped free-list if contention shows in the benchmark). Bound default:
  `min(availableProcessors * 64, 4096)`. Above the bound, `release()` drops the scratch for GC
  instead of growing forever. Documented: this is a *cache*, not a leak-free arena — a burst of
  100 k connections allocates 100 k scratches, but only the bound survives it.
- `ServerLifecycle.java` — `start`/`startAndBlock`/`stop`, the `acceptLatch`, the
  `activeSockets` set, and the two-stage graceful shutdown (`EX-32`).
- `TransportFactory.java` — package-private construction, consumed by `ServerHandle.create`
  (`EX-34`).

Created — `dev.relism.flash.http1`:
- `Http1Connection.java` — implements `ConnectionProtocol`. The keep-alive request loop
  (`HttpServer.process` lines 303-345). Sole responsibility: drive request→route→handle→respond
  for one connection.
- `Http1ResponseWriter.java` — `writeResponse`, `writeStreamingBody`, `relay`,
  `writeStatusPhrase`, `writeLong`, `writeHex`, `writeChunked` (lines 469-563).
- `Http1ChunkedEncoder.java` — split out of the above if it does not stay trivially small.
- `Http1KeepAlive.java` — `isKeepAlive` (line 454), fixed per `EX-13`.

Created — `dev.relism.flash.websocket`:
- `WebSocketUpgrade.java` — `isWebSocketUpgrade`, `connectionContainsUpgrade`,
  `tokenEqualsIgnoreCase`, `performHandshake` (lines 373-424).
- `WebSocketLoop.java` — `runWsLoop` (lines 428-450).
- `WebSocketFrameCodec.java` — frame header encode/decode extracted from `WebSocketSession`.

Modified:
- `HttpServer.java` — **deleted**, or reduced to a thin `ServerHandle` implementation that
  composes the above. Prefer deletion; `ServerHandle` is the public contract and
  `TransportFactory` can build a `FlashTransport` that implements it.
- `WebSocketSession.java` — `EX-01`, `EX-11`, `EX-12`.
- `ServerHandle.java` — `EX-34`.
- `FastPathRouterImpl.java` — drop `FastPathRouterContext`'s `ThreadLocal`s in favour of the
  scratch (`EX-06`); the router now takes the scratch as a parameter or reads it from the
  request's context.
- `models/Response.java`, `models/Request.java` — only as needed to accept a scratch; the full
  pooling refactor is Phase 6.
- `http/DateHeader.java` — new (`EX-16`).

### Tasks

1. **Extract in the order listed above**, one commit per extracted component, each commit
   green. Do not combine extraction with behaviour change except where an `EX` item explicitly
   requires it — and when it does, make it a separate commit immediately after the extraction
   commit, so `git log` shows "moved" and "fixed" separately.
2. **`ConnectionScratch` + `ScratchPool`** (`EX-06`). Remove every `ThreadLocal` from
   `HttpServer` and `FastPathRouterImpl`. Correct the false Javadoc at `HttpServer.java:56-58`
   as part of the move — the replacement documentation must state plainly: *"With virtual
   threads, a `ThreadLocal` is per connection, not per core. Scratch is therefore explicit and
   pooled."*
3. **`ReentrantLock` for WebSocket writes** (`EX-01`). Replace both `synchronized (out)` blocks.
   Add a Javadoc note explaining the Java 21 pinning rationale and referencing JEP 491, so that
   whoever moves the project to JDK 24+ knows the constraint can be revisited.
4. **WebSocket frame header bulk read** (`EX-11`) and **full RFC 6455 validation** (`EX-12`):
   continuation-frame reassembly with a bounded total message size, mandatory client masking
   enforcement, opcode validation, control-frame constraints (≤125 bytes, FIN set, not
   fragmented), correct close codes.
5. **`Connection` token-list parsing** (`EX-13`). One shared scanner in
   `dev.relism.flash.bytes.ByteScan` (created ahead of Phase 4 if needed, or temporarily in
   `WebSocketUpgrade` and moved in Phase 4 — prefer creating `ByteScan` now).
6. **HEAD suppression** (`EX-14`) in `Http1ResponseWriter`: compute and emit `Content-Length`,
   skip the body write.
7. **Content-Type / Content-Length correctness** (`EX-15`): skip empty `Content-Type`; skip
   `Content-Length` for 204/304/1xx; skip the body for those statuses too.
8. **`DateHeader`** (`EX-16`): one daemon thread, `volatile byte[]` holding the complete
   pre-encoded `Date: Sun, 06 Nov 1994 08:49:37 GMT\r\n` line, refreshed every second, written
   by `Http1ResponseWriter` with a single `write(byte[])`. Add a `FlashConfiguration.sendDate`
   flag (default `true`) for users who front Flash with a proxy that already adds it.
9. **Graceful shutdown** (`EX-32`): `ServerLifecycle.stop()` becomes two-stage — stop accepting,
   mark connections draining (h1 sets `Connection: close` on the next response; h2 will send
   GOAWAY in Phase 8), wait up to `shutdownDrainTimeoutMs`, then force-close.
10. **Verify no behaviour change** for everything not covered by an `EX` item. The existing
    test suite is the oracle; it must pass without modification apart from import updates.

### Zero-alloc contract
Strictly better than before this phase: the per-virtual-thread `ThreadLocal` allocations are
replaced by pooled per-connection scratch, and the WebSocket header read stops allocating
nothing but stops syscalling per byte. No new steady-state allocation is introduced.

### Safety checks
- [x] `ScratchPool` is bounded and cannot grow without limit — `ScratchPoolTest.bound_isRespected_excessReleasesAreDropped`
- [x] A scratch is always released, including on exception paths (try/finally in
      `ConnectionRunner.handle`) — `ConnectionRunnerTest.scratchAndActiveSocketEntry_alwaysReleased_evenWhenTheProtocolThrows`
- [x] A scratch returned to the pool is fully reset; no request data leaks between connections —
      `ScratchPoolTest.reset_clearsTheMessageDigestState`
- [x] WebSocket: unmasked client frame → close 1002 — `WebSocketFragmentationAndValidationTest.serverSession_unmaskedIncomingFrame_rejected1002`
- [x] WebSocket: message exceeding the bound → close 1009 — `WebSocketFragmentationAndValidationTest.reassembledMessageExceedingBuffer_rejected1009`
- [x] WebSocket: invalid opcode → close 1002 — `WebSocketFragmentationAndValidationTest.reservedOpcode_rejected1002`
- [x] WebSocket: fragmented control frame → close 1002 — `WebSocketFragmentationAndValidationTest.fragmentedControlFrame_rejected1002`

### Tests
- [x] All existing tests pass with only import changes (277 pre-Phase-2 tests unmodified in
      behavior; two files touched only for the log-string/class-relocation, see PR).
- [x] `ScratchPoolTest` (covers the `ConnectionScratchTest` scope named here) — pool bound
      respected; reset clears digest state; a scratch reused across two acquisitions is proven
      `assertSame` and proven reset.
- [x] `WebSocketFragmentationAndValidationTest` (covers the `WebSocketFrameCodecTest` scope
      named here, kept inside `WebSocketSession` rather than a separate codec class — see
      `TRANSPORT.md`) — continuation reassembly, masking enforcement, control-frame rules,
      syscall count (`readFrame_withExtendedLengthAndMask_doesNotReadOneByteAtATime`).
- [x] `Http1ResponseWriterTest` — HEAD, 204, 304, 1xx, `ContentType.NONE`, `Date` present/absent.
- [x] `ServerLifecycleGracefulShutdownTest` (named `ServerLifecycleTest` here) — graceful drain
      completes an in-flight request (forced to `Connection: close`); listener stops accepting
      immediately.
- [x] `PackageBoundaryTest` — a source-scan architecture test (decision recorded in the test's
      own Javadoc: no ArchUnit dependency yet, and one import check per package pair does not
      need one): `dev.relism.flash.http1` must not import `dev.relism.flash.http2` and vice versa.

### Docs
- `README.md` architecture section (lines 257-274) rewritten to reflect the new component
  layout.
- `flash/docs/http2/TRANSPORT.md` — the transport architecture: listeners, accept loop, connection
  runner, scratch pooling, the `ConnectionProtocol` seam. This is the document the h2 phases
  will extend.

### DoD
- [x] `HttpServer.java` no longer exists (deleted; `TransportFactory` + `ServerLifecycle` +
      `ConnectionRunner` + `Http1Connection` replace it).
- [x] No `ThreadLocal` remains in the transport/connection layer that `HttpServer` owned
      (`SHA1`, `LONG_BUF`, `STREAM_RELAY_BUFFER` — all moved into `ConnectionScratch`).
      **Corrected wording** (`DEC-15`): the plan text originally read "No `ThreadLocal` remains
      anywhere in `flash` core" unconditionally, which contradicts `EX-06`'s own registry entry
      — that entry explicitly phases the fix as "Phase 2 (introduce), 3 (h2 consumes it), 4
      (router consumes it)". `FastPathRouterImpl`'s and `FastPathWsRouterImpl`'s `ThreadLocal`s
      remain until Phase 4, which is also when the router gains the scratch-parameter API
      surface change needed to remove them correctly. Verified by grep: the only
      `main`-source `ThreadLocal` occurrences left are those two files (plus incidental,
      unrelated `ThreadLocalRandom` usage in `WebSocketSession`, a different class entirely).
- [x] No `synchronized` block in `flash` core encloses a blocking I/O call. Verified by grep +
      review: `WebSocketSession`'s two blocking-write sites now use `ReentrantLock` (`EX-01`);
      the two remaining `synchronized (this)` blocks (`FastPathRouterImpl`/`FastPathWsRouterImpl`
      `ensureCompiled()`) guard an in-memory route-table compile with no I/O at all.
- [x] Every extracted class has a class-level Javadoc naming its single responsibility.
- [ ] h1 benchmark: no regression; ideally an improvement from `EX-06` and `EX-11`. **Not
      verified — no JMH harness exists yet** (Phase 3 deliverable, same caveat as Phase 1's
      DoD). Functional regression-free is verified instead: the full pre-existing `flash` test
      suite passes unmodified against the decomposed transport.

---

## Phase 3 — The serialized frame writer · **GO/NO-GO GATE**

**Goal.** Build and prove the one component whose failure would invalidate the entire project:
the connection-level serialized writer, with a happy path that costs one uncontended CAS.

**Why now.** This is the only genuinely novel architectural risk in HTTP/2 for a codebase built
on "one thread owns the socket". Everything else — frames, HPACK, flow control — is
well-understood table-driven work with known cost. If the writer cannot deliver, the project
should stop here having spent one phase, not ten.

**This phase is deliberately placed before the frame parser**, which is the fun part and also
the least risky part.

### The problem, precisely

Today, one thread owns the socket and writes to it without coordination.
`Http1ResponseWriter` issues a sequence of writes and nobody else is writing.

Under HTTP/2, N streams share one connection and their frames must interleave. Every write must
pass through a serialization point that does not exist today. A lock taken naively per frame
costs more than every allocation the codebase has ever saved.

### The design (three layers)

**Layer 1 — serialize outside the lock.**
Never hold the lock across many small writes. A stream builds its complete output (frame
header + HPACK block + payload) into a **per-stream scratch buffer, reused**, then takes the
lock once and issues a **single** bulk `write`. The lock is held for the duration of a
`System.arraycopy` into the connection's output buffer (or one `write` syscall), not for a
serialization. This is why `EX-27` (collapse `writeResponse` into one write) is a prerequisite
and lands in Phase 6 for h1 too.

**Layer 2 — `ReentrantLock`, never `synchronized`.**
Java 21: a virtual thread blocking inside `synchronized` pins its carrier;
blocking on a `ReentrantLock` unmounts it. Non-negotiable. See `EX-01`.

**Layer 3 — `tryLock()` fast path with an intrusive MPSC fallback.**
The overwhelmingly common instant, even on a multiplexed connection, has exactly **one** stream
wanting to write: a browser calling one API endpoint, a gRPC unary call. In that case
`tryLock()` on an uncontended lock is **one successful CAS**; the thread writes inline and
releases. No handoff, no queue, no allocation, no context switch.

When `tryLock()` fails — i.e. there is genuine contention, i.e. you are genuinely multiplexing —
the stream publishes its pending write and returns. The current lock holder drains the queue
before releasing. The queue is an **intrusive** Vyukov-style MPSC linked queue: `Http2Stream`
*is* the node (it carries a `next` field), so enqueue is one CAS and zero allocation.

```
happy path (1 active writer):  tryLock → memcpy → write → unlock      ≈ 1 CAS
contended  (N active writers): tryLock fails → CAS enqueue → return
                               current holder drains before unlocking
```

Correctness requirement: **no lost wakeup.** The classic hazard is: producer enqueues, then the
holder checks the queue and finds it empty, then unlocks — leaving the item stranded. The
standard fix is the re-check-after-unlock pattern: after `unlock()`, re-read the queue head; if
non-empty, attempt `tryLock()` again and drain. This must be implemented deliberately, with the
race documented in the Javadoc, and verified by a dedicated stress test.

### Files

Created:
- `flash/src/main/java/dev/relism/flash/http2/frame/Http2FrameWriter.java`
- `flash/src/main/java/dev/relism/flash/http2/frame/WriteIntent.java` — the interface a stream
  implements to describe "serialize yourself into this buffer". Implemented by `Http2Stream`
  and by connection-level singletons (SETTINGS ACK, PING ACK, GOAWAY, WINDOW_UPDATE) so that
  connection frames use the same path as stream frames — one writer, no exceptions.
- `flash/src/main/java/dev/relism/flash/http2/frame/IntrusiveMpscQueue.java` — the Vyukov queue,
  operating on a `Node` interface that `Http2Stream` implements.
- `flash/src/test/java/dev/relism/flash/http2/frame/Http2FrameWriterTest.java`
- `flash/src/test/java/dev/relism/flash/http2/frame/Http2FrameWriterStressTest.java`
- `flash/src/jmh/java/dev/relism/flash/http2/FrameWriterBenchmark.java` (or a `flash-bench`
  submodule — decide and record in `DECISIONS.md`; a `jmh` profile on the `flash` module is
  simplest and avoids a new artifact).

Modified:
- Root `pom.xml` — add a `jmh` profile with `jmh-core` and `jmh-generator-annprocess`. Not
  bound to the default build; CI runs it in a separate, non-blocking job until Phase 17 turns
  the gate on.

### Tasks

1. Implement `Http2FrameWriter` with the three-layer design. Public surface, deliberately tiny:
   ```java
   /** Serializes and writes one frame. Returns when the bytes are in the socket buffer
    *  or safely queued behind another writer. Never blocks on another stream's I/O
    *  while holding the lock. */
   void write(WriteIntent intent) throws IOException;

   /** Flushes any queued intents. Called by the demux loop when it has nothing to read. */
   void drain() throws IOException;
   ```
2. Implement `IntrusiveMpscQueue` with `offer(Node)` (one CAS on the tail) and `poll()`
   (producer-consumer safe, single consumer — the lock holder). Document the memory-ordering
   requirements explicitly (which fields are `volatile`, which use `VarHandle`
   `setRelease`/`getAcquire`). Prefer `VarHandle` over `AtomicReferenceFieldUpdater`.
3. Implement the lost-wakeup-free unlock protocol and document it with an ASCII interleaving
   diagram in the Javadoc.
4. Handle the **partial-write / backpressure** case: if the socket write blocks because the
   kernel send buffer is full, the writer is holding the lock while blocked. This is
   unavoidable (someone must block) but must not pin a carrier — hence `ReentrantLock` — and
   must be bounded by a write timeout so a stalled peer cannot hold the connection's writer
   forever. Add `Http2Limits.WRITE_TIMEOUT_MS` and a documented behaviour (write timeout →
   connection error → GOAWAY → close).
5. Write the stress test: N producer virtual threads (N ∈ {1, 2, 8, 64, 256}) each writing M
   frames with distinguishable payloads into a mock sink; assert every byte of every frame
   arrives, in a valid frame-boundary-respecting order (frames may interleave with each other,
   but a single frame's bytes must never be split by another frame's bytes), with no
   duplication and no loss. Run under `-Djdk.virtualThreadScheduler.parallelism=1` as well, to
   surface pinning and lost wakeups.
6. Write the JMH benchmark measuring, for N ∈ {1, 2, 4, 8, 16, 64} concurrent writer virtual
   threads: throughput (frames/s), latency percentiles (p50/p99/p999), and
   `gc.alloc.rate.norm`. Also benchmark the three candidate designs against each other so the
   choice is defended by numbers, not assertion:
   - (a) plain `ReentrantLock.lock()` per frame
   - (b) `tryLock()` + intrusive MPSC (the proposed design)
   - (c) a dedicated writer virtual thread fed by the MPSC queue (always-handoff)
7. Record the results in `flash/docs/http2/DECISIONS.md` as `DEC-09`, with the raw numbers.

### Zero-alloc contract
- `write(WriteIntent)` must be **0 B/op** on both the uncontended and the contended path.
  Verified by `-prof gc` in the benchmark. This is the phase's hardest requirement: it rules
  out lambda capture, `Optional`, boxed integers in the queue, and any per-call node object.
- The intrusive queue allocates nothing per enqueue by construction.

### Safety checks
- [x] Write timeout bounded and enforced (`Http2Limits.WRITE_TIMEOUT_MS`, scan-based reaper —
      see `WriteTimeoutReaper`, and `WRITER.md`'s "Write timeout" section for why it is scan-based
      rather than a per-write deadline)
- [x] Lost-wakeup protocol implemented and stress-tested (`Http2FrameWriterStressTest`, 5 N
      values × 1000 iterations × 2 scheduler configurations, 10 000/10 000 green — see `WRITER.md`)
- [x] A frame's bytes are never interleaved with another frame's bytes (proven by the stress
      test's frame-boundary reassembly/validation, not merely asserted)
- [x] Queue depth bounded — each `WriteIntent` is at most one node (intrusive linkage via
      `mpscNext`/`setMpscNext`), so queue depth is inherently bounded by the number of distinct
      intents that can be concurrently in flight, not by an unbounded external counter
- [x] Exception inside a sink write does not leave the lock held or the queue corrupted
      (`Http2FrameWriterTest#exceptionFromSink_doesNotLeaveTheLockHeld`)

### Gate criteria — the project continues only if all of these hold
- [x] N=1: **0 B/op** (0.0015 B/write differential vs. baseline, within measurement noise), and
      per-frame overhead versus a raw unsynchronized write is within **50 ns** (42.6 ns point
      estimate, ≤47.9 ns at the 99.9% CI's worst case).
- [x] N=64: throughput does not collapse (**65.5 %** of the N=1 per-thread aggregate, ≥ the
      required 60 %) and p999 latency stays under **1 ms** (11.8–14.2 µs measured; see `WRITER.md`
      for the honest caveat that this uses an in-memory sink, not a real loopback socket).
- [x] No carrier pinning observed under `-Djdk.tracePinnedThreads=full`.
- [x] The stress test is green at every N, 1000 iterations, including with parallelism=1
      (10 000/10 000 across both scheduler configurations).

All criteria met — **GO**. Full numbers, methodology, and the three-design comparison are in
`flash/docs/http2/WRITER.md` and `DECISIONS.md` (`DEC-09`).

### Docs
- [x] `flash/docs/http2/WRITER.md` — the full design, the three layers, the lost-wakeup protocol with its
  diagram, the benchmark numbers, and the explicit statement of what the design costs on the
  happy path (one uncontended CAS) versus what it saves.

### DoD
- [x] All gate criteria met and recorded.
- [x] `DEC-09` written with raw numbers.
- [x] `flash/docs/http2/WRITER.md` complete.

---

## Phase 4 — Byte-layer foundations

**Goal.** Extract and strengthen the protocol-neutral byte machinery that both protocols use,
and cash in the allocation and scanning wins that the existing code left on the table.

**Why now.** Every subsequent phase consumes these primitives. Doing it after the frame reader
would mean rewriting the frame reader.

### EX items
`EX-04`, `EX-05`, `EX-09`, `EX-19`, `EX-25`, `EX-26`, `EX-33` — **plan correction**: `EX-06`'s
router half (removing `FastPathRouterImpl`/`FastPathWsRouterImpl`'s `ThreadLocal`s) belongs here
too, per `EX-06`'s own registry text ("Phase: 2 (introduce), 3 (h2 consumes it), **4 (router
consumes it)**") and `ConnectionScratch`'s own Phase-2-era Javadoc, but was missing from this
line — the same class of omission `DEC-12` already recorded for Phase 1. Fixed in place here;
see `DECISIONS.md`, `DEC-19`, for the router-half fix itself (and why it does not extend
`ConnectionScratch` as that Javadoc originally assumed).

### Files

Created — `dev.relism.flash.bytes`:
- `ByteScan.java` — the single home for: `indexOf(byte)`, `indexOfCrLfCrLf` (SWAR),
  `equalsIgnoreCase(view/array, String)`, `equalsIgnoreCaseAscii(array, array)`,
  token-list iteration (`Connection: a, b, c`), `tchar` validation, hex/decimal parsing,
  and the case-insensitive 32-bit name hash used by the header index.
  Every method static, every method zero-alloc, every method with a scalar reference
  implementation used by tests as the oracle for the SWAR version.
- `ArrayBackedByteView.java` — capability interface:
  ```java
  public interface ArrayBackedByteView extends ByteView {
      byte[] array();
      int offset();
  }
  ```
  Implemented by every contiguous view. Enables single-allocation `String` construction
  (`EX-25`) and single-`System.arraycopy` copies.
- `SegmentedByteView.java` — a view over K segments (`byte[][]` + offsets + lengths), for the
  rare HPACK block that spans CONTINUATION frames. Returns `false` from `supportsLong()`.
  Reusable: `reset(segments, offsets, lengths, count)`.
- `PooledSlice.java` — reusable slice implementing `ArrayBackedByteView`, with an explicit
  documented lifetime. Replaces the anonymous views in `HeaderMap`, `QueryParams`, `PathParams`.
- `SlicePool.java` — a small fixed-size ring of `PooledSlice` per `ConnectionScratch`.
- `ByteWriter.java` — index-based writes into a growable `byte[]`: `writeByte`,
  `writeBytes(byte[])`, `writeBytes(byte[],int,int)`, `writeDecimal(long)`, `writeHex(int)`,
  `writeAsciiLower(String)`, `writeUInt16/24/31/32` (big-endian, for h2 frames). Bounds-checked
  growth, never allocates when the buffer already fits. This is what both
  `Http1ResponseWriter` and `Http2FrameWriter` serialize into.
- `Pairs.java` — `pack(int hi, int lo)`, `hi(long)`, `lo(long)`, documented as the
  allocation-free pair return idiom; replaces the four hand-rolled copies of
  `((long) x << 32) | y` in `HeaderMap`, `QueryParams` and elsewhere.

Modified:
- `routing/routers/fastpathrouter/FastPathViews.java` — `RequestByteView`, `SocketByteView`,
  `StringByteView` implement `ArrayBackedByteView` **and** override
  `supportsLong()`/`longAt(int)` (`EX-04`). `MethodPathByteView` keeps the `false` default and
  gains a Javadoc explaining why (it is segmented by construction).
- `models/HeaderMap.java` — header index (`EX-09`), pooled slices (`EX-05`).
- `models/QueryParams.java` — pooled slice, clean-value fast path (`EX-26`).
- `models/PathParams.java` — pooled slice, reusable arrays, single-allocation `get` (`EX-25`).
- `models/Request.java` — single-allocation `path()` (`EX-25`).
- `routing/routers/fastpathrouter/FastPathRouterImpl.java` — reusable `PathParams` from the
  scratch (`EX-19`).
- `RequestParser.java` — consume `ByteScan` instead of its private `find`/`equalsIgnoreCase`
  helpers; SWAR header-end scan (`EX-33`).

### Tasks

1. Build `ByteScan` with paired scalar and SWAR implementations. The SWAR CRLFCRLF scan uses
   the standard "has zero byte" bit trick on `long`s read via `VarHandle`
   (`MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder())` — note
   native order, and document why endianness does not matter for a byte-equality scan but does
   for the position extraction). **Property-test SWAR against scalar** on random inputs of every
   length 0..256 with the target at every position, including unaligned starts.
2. `supportsLong()`/`longAt()` on contiguous views (`EX-04`). `longAt(i)` reads 8 bytes at
   `offset + i` via the same `VarHandle`, with the contract that the caller guarantees
   `i + 8 <= length()` — matching whatever `fpr-core`'s `ByteCompare` assumes. **Verify the
   assumed contract by testing against `fpr-core` directly**, not by reading its bytecode: write
   a test that builds a router with long literal segments and asserts matches are still correct
   after enabling the long path. A wrong endianness or a wrong bounds assumption here produces
   silently mis-routed requests, which is the worst possible failure mode.
3. Measure the `EX-04` win on the h1 router benchmark. If it is negative or within noise,
   record that in `DECISIONS.md` and keep the implementation anyway only if it is neutral;
   revert if it costs.
4. Header index (`EX-09`): at `HeaderMap.reset()`, populate reusable `int[]` arrays with per
   header `(nameOff, nameLen, valOff, valLen)` and a parallel `int[]` of case-insensitive name
   hashes. `findFirst(String)` computes the name hash once (the name is usually a compile-time
   constant at the call site — consider a `HeaderName` value type with a cached hash for
   library-internal lookups, and record the decision) and then compares hashes before memcmp.
   Arrays grow to the connection high-water mark and are sized from `Http1Limits.MAX_HEADER_COUNT`.
5. Pooled slices (`EX-05`) in `HeaderMap.view`, `QueryParams.view`, `PathParams.view`. Extend
   each class's existing lifetime-contract Javadoc to cover slice reuse precisely: *"the
   returned view is valid until the Nth subsequent `view()` call on the same object, where N is
   the pool size, or until the end of the request — whichever comes first."*
6. Reusable `PathParams` (`EX-19`) held on `ConnectionScratch`, repositioned by
   `FastPathRouterImpl.route`. Remove the three per-request array allocations.
7. Single-allocation `String` construction (`EX-25`) wherever a view is `ArrayBackedByteView`.
8. `QueryParams.decode` clean-value fast path (`EX-26`).
9. Replace the hand-rolled pair packing with `Pairs`.

### Zero-alloc contract
- After this phase, an h1 `GET /users/{id}` request that reads three headers and one path
  param must be **0 B/op** end to end except for the user-facing `String`s the handler
  explicitly asks for. Add this as a JMH allocation test now; it becomes a CI gate in Phase 17.

### Safety checks
- [x] `longAt` bounds contract documented (`FastPathViews`'s `longAtLittleEndian` Javadoc,
      `ArrayBackedByteView`/`ByteScan` class Javadocs) and verified against `fpr-core`'s own
      `ByteCompare` directly (`FastPathViewsLongAtTest`) — no defensive runtime assert was added
      for the bounds contract itself, since `ByteCompare` never calls `longAt(i)` without first
      checking `i + 8 <= length()` (confirmed from its decompiled bytecode), making a check here
      dead code on every real call path; documented as such rather than added anyway.
- [x] Header index arrays bounded by `MAX_HEADER_COUNT`; overflow is impossible because Phase 1
      already rejects over-limit requests — asserted (`HeaderMap.ensureIndexCapacity`), not
      silently truncated; exercised up to the exact limit by
      `HeaderMapIndexTest#growsPastInitialIndexCapacity_upToMaxHeaderCount_andStaysCorrect`.
- [x] SWAR scan never reads past the array bound — `ByteScanTest`/`ByteScanFuzzTest` cover every
      length 0–256 exhaustively plus 20 000 fully-random fuzz trials per SWAR method, including a
      match at the very last valid byte and buffer lengths not a multiple of 8.
- [x] Pooled slice reuse cannot alias two live views the caller believes are independent —
      documented on `SlicePool`/`PooledSlice`/every `view()` method, and demonstrated (not just
      asserted) by `SlicePoolTest#wraparoundAliasesThePreviouslyReturnedSlice` and the analogous
      tests in `HeaderMapIndexTest`, `QueryParamsFastPathTest`, `PathParamsTest`.

### Tests
- `ByteScanTest` — property tests, SWAR vs scalar, every boundary.
- `ByteScanFuzzTest` — random bytes, assert no exception and agreement with scalar.
- `FastPathViewsLongAtTest` — `longAt` correctness, and end-to-end routing correctness with the
  long path enabled (the critical test from task 2).
- `HeaderMapIndexTest` — lookup correctness with duplicate names, case variations, 0 headers,
  `MAX_HEADER_COUNT` headers, an allocation-identity assertion, and the pool-wraparound hazard.
- `QueryParamsFastPathTest`, and the pool-wraparound/reuse cases added directly to the existing
  `PathParamsTest` and `FastPathRouterImplTest` — **plan correction**: no separate
  `PathParamsReuseTest` file was created; the reuse-across-many-requests case
  (`FastPathRouterImplTest#route_reusesScratchAcrossManyRequests_includingGrowingParamCapacity`)
  exercises `PathParams`'s reusable path through the router that actually owns it, which is a more
  realistic test than a `PathParams`-only unit test would have been.
- Existing `models`/`routing` tests: **not** unmodified as originally written here — `route()`
  gained a `scratch` parameter (`EX-06`, `DEC-19`), so every direct caller (`FastPathRouterImplTest`,
  `AbstractRouterTest`, `AbstractWsRouterTest`) needed a one-line update. All pass; 395/395 across
  the whole module, including full socket-level `HttpServer*Test` suites exercising the real
  `Http1Connection` path end to end.

### Docs
- [x] `flash/docs/http2/BYTES.md` — the byte-layer primitives, the `ByteView` capability hierarchy
  (`ByteView` → `ArrayBackedByteView` → concrete; `SegmentedByteView` as the deliberate
  non-array-backed case), the `supportsLong` contract, and the pooled-slice lifetime rules.
- [x] `HeaderMap`'s class Javadoc updated in place (the `EX-09` index, the pooled-`view()`
  contract) as part of its Phase 4 rewrite.

### DoD
- [~] h1 happy path is 0 B/op in JMH — **partially, honestly**: Phase 4's own scope (header
  lookup, path-param extraction, query decoding) measures at **≈0 B/op**
  (`RequestPipelineBenchmark.router_staticRoute`/`router_parametricRoute`, ≈0 B/op;
  `HeaderMapIndexTest`'s identity-based allocation check). The full h1 pipeline is **not**
  literally 0 B/op yet: 120.008 B/op measured, 100% attributable to `Request`/`RequestBody`/
  `RequestLine` construction (`EX-21`/`EX-22`), which is explicitly Phase 6 scope, not Phase 4's.
  See `DECISIONS.md`, `DEC-20`, for the full breakdown and why this is not a Phase 4 regression.
- [x] h1 throughput improved or unchanged; numbers recorded — `EX-33`'s SWAR scan is 35.4% faster
  than scalar (kept); `EX-04`'s word-at-a-time path is 32.1% faster than byte-at-a-time at the
  mechanism level (kept — see `DEC-20` for why today's router benchmark doesn't yet show this
  directly). No regression found anywhere measured.
- [~] Every anonymous `ByteView` allocation in `flash` core is gone — **two deliberate,
  documented exceptions remain** (`QueryParams.view`, `PathParams.view`, the fallback path for a
  non-array-backed source — structurally unreachable on the real request path today, kept because
  both constructors are `public`; see `BYTES.md`). Every allocation on the actual hot path is
  gone; grep `new ByteView()` and read the two remaining hits' Javadocs before treating this as
  incomplete.
- [x] `flash/docs/http2/BYTES.md` complete.

---

## Phase 5 — Frame layer

**Goal.** Read, validate and write HTTP/2 frames. No connection semantics, no streams, no
HPACK — just the 9-byte header and the payload boundary, correctly and safely.

**Why now.** Everything above it needs frames. It depends only on Phase 3 (the writer) and
Phase 4 (the byte layer).

### Background for the implementer

The frame header is nine bytes:

```
+-----------------------------------------------+
|                 Length (24)                   |
+---------------+---------------+---------------+
|   Type (8)    |   Flags (8)   |
+-+-------------+---------------+-------------------------------+
|R|                 Stream Identifier (31)                      |
+=+=============================================================+
|                   Frame Payload (0...)                      ...
+---------------------------------------------------------------+
```

This is why the h2 parser is *simpler* than the h1 one: `RequestParser` must scan for `\r\n\r\n`
and then handle chunked framing; here the length is stated up front, so nothing is ever
scanned. `Http2FrameReader` is a length-prefixed reader and nothing more.

### Files

Created:
- `h2/frame/FrameType.java` — constants `DATA(0x0)`, `HEADERS(0x1)`, `PRIORITY(0x2)`,
  `RST_STREAM(0x3)`, `SETTINGS(0x4)`, `PUSH_PROMISE(0x5)`, `PING(0x6)`, `GOAWAY(0x7)`,
  `WINDOW_UPDATE(0x8)`, `CONTINUATION(0x9)`, plus a per-type validation descriptor table
  (see `FrameValidator`).
- `h2/frame/FrameFlags.java` — `END_STREAM(0x1)`, `ACK(0x1)`, `END_HEADERS(0x4)`,
  `PADDED(0x8)`, `PRIORITY(0x20)`, with predicate helpers. Note the deliberate collision:
  `0x1` is `END_STREAM` on DATA/HEADERS and `ACK` on SETTINGS/PING — document it, because
  conflating them is a classic bug.
- `h2/frame/FrameHeader.java` — a **flyweight**: fields `length`, `type`, `flags`, `streamId`,
  `payloadOffset`, plus `reset(byte[] buf, int off)`. One instance per connection, never
  allocated per frame. Mirrors the existing `WebSocketFrame` reuse idiom.
- `h2/frame/Http2FrameReader.java` — reads into the connection read buffer and populates the
  flyweight. Handles the case where a frame is larger than the current buffer (grow, bounded by
  `MAX_FRAME_SIZE_LOCAL`) and the case where a frame spans multiple socket reads.
- `h2/frame/FrameValidator.java` — table-driven RFC validation, see tasks.
- `h2/frame/Padding.java` — RFC 9113 §6.1/§6.2 padding: read the pad length byte, validate that
  `padLength < length`, expose the unpadded payload range. Padding is **not** optional to
  support: any client may send it.

### Tasks

1. `Http2FrameReader.readFrameHeader()`: read exactly 9 bytes (via the buffered source from
   Phase 1), decode with shifts:
   ```java
   length   = ((b0 & 0xFF) << 16) | ((b1 & 0xFF) << 8) | (b2 & 0xFF);
   type     =   b3 & 0xFF;
   flags    =   b4 & 0xFF;
   streamId = ((b5 & 0x7F) << 24) | ((b6 & 0xFF) << 16) | ((b7 & 0xFF) << 8) | (b8 & 0xFF);
   ```
   The high bit of `b5` is the reserved bit `R`: RFC 9113 §4.1 says it MUST be ignored on
   receipt. Mask it, do not error. Document that.
2. `readPayload()`: ensure `length` bytes are available in the buffer, growing it if needed,
   bounded by `MAX_FRAME_SIZE_LOCAL`. A frame declaring a length above the advertised
   `SETTINGS_MAX_FRAME_SIZE` is a connection error `FRAME_SIZE_ERROR` — **check before
   allocating or reading**, so a 16 MB declared length from a hostile peer never causes a 16 MB
   buffer growth.
3. `FrameValidator` — a static table indexed by frame type, each entry declaring:
   - minimum and maximum payload length (e.g. `RST_STREAM` exactly 4, `PING` exactly 8,
     `WINDOW_UPDATE` exactly 4, `GOAWAY` at least 8, `SETTINGS` a multiple of 6,
     `PRIORITY` exactly 5)
   - whether stream id must be zero (`SETTINGS`, `PING`, `GOAWAY`) or non-zero (`DATA`,
     `HEADERS`, `PRIORITY`, `RST_STREAM`, `CONTINUATION`); `WINDOW_UPDATE` allows both
   - which flags are defined (undefined flags MUST be ignored, not rejected — RFC 9113 §4.1)
   - whether the type is flow-controlled
   Violations raise `Http2Exception(FRAME_SIZE_ERROR)` or `Http2Exception(PROTOCOL_ERROR)` per
   the RFC's specific requirement for each case. **Read the RFC per type; the error code is not
   uniform.** For example, a `SETTINGS` frame whose length is not a multiple of 6 is
   `FRAME_SIZE_ERROR`, while a `SETTINGS` frame with a non-zero stream id is `PROTOCOL_ERROR`.
4. Unknown frame types (`type > 0x9`) MUST be **ignored** — read and discard the payload,
   do not error (RFC 9113 §4.1, this is the extension mechanism). Exception: an unknown frame
   type arriving in the middle of a header block (between HEADERS/CONTINUATION and
   END_HEADERS) is a `PROTOCOL_ERROR` (§6.10). This interaction is a classic conformance miss.
5. Padding support (`Padding.java`) for DATA and HEADERS. `padLength >= length` → connection
   error `PROTOCOL_ERROR`. Padding bytes MUST be ignored by the receiver but MUST still be
   counted against flow control for DATA.
6. Wire `Http2FrameWriter` (Phase 3) to emit frame headers via `ByteWriter.writeUInt24` /
   `writeUInt8` / `writeUInt31`. Provide `beginFrame(type, flags, streamId)` /
   `endFrame()` on the write scratch so the length is back-patched after the payload is
   serialized — the standard technique, and the reason the writer serializes into a buffer
   rather than streaming.
7. `PRIORITY` frames: parse, validate the 5-byte length, and **discard**. RFC 9113 deprecates
   priority signalling (§5.3.2: "endpoints... SHOULD ignore"), but a frame that arrives must
   still be consumed and must not error. Document this as an intentional non-implementation.
8. `PUSH_PROMISE` received from a client is a connection error `PROTOCOL_ERROR` (only servers
   send it, and we advertise `SETTINGS_ENABLE_PUSH = 0`). We never send it.

### Zero-alloc contract
- Reading, validating and discarding a frame: **0 B/op**. No `FrameHeader` allocation, no
  payload copy at this layer (the payload stays in the read buffer; copies happen above, per
  the layer that needs to retain it).
- Writing a frame header: 0 B/op (writes into the existing scratch).
- [x] **Measured**, not just asserted: `FrameLayerBenchmark` (`-prof gc`) — read+validate+consume
      0.002 B/op, write 10⁻⁴ B/op, both indistinguishable from zero. `DECISIONS.md`, `DEC-21`.

### Safety checks
- [x] Declared length checked against `SETTINGS_MAX_FRAME_SIZE` **before** any buffer growth —
      `Http2FrameReader.readFrame` checks `declaredLength > MAX_FRAME_SIZE_LOCAL` immediately
      after decoding the header, before the payload-sized `ensureAvailable` call that would grow
      the buffer.
- [x] Buffer growth bounded and monotonic — grows only to accommodate `9 + declaredLength`,
      itself already bounded by the check above; never shrinks (matches `RequestParser`'s own
      buffer policy, not yet pool-released — no per-connection buffer pool exists before Phase 13).
- [x] Per-type length/stream-id/flag validation table complete for all 10 types — `FrameType`'s
      constants + `FrameValidator`, one `FrameValidatorTest` case per RFC-mandated rejection.
- [x] Unknown types ignored; unknown types inside a header block rejected —
      `FrameValidator.validate`'s `insideHeaderBlock` parameter,
      `unknownType_outsideHeaderBlock_isIgnoredNotRejected`/`unknownType_insideHeaderBlock_isProtocolError`.
- [x] Reserved bit masked, not rejected — `FrameHeader.reset` masks it out of `streamId()`;
      `reservedBitInStreamId_isMaskedNotRejected`.
- [x] Padding length validated against frame length — `Padding.unpad`, `PaddingTest`'s boundary
      cases (`padLength == payloadLength - 1` valid, `padLength >= payloadLength` rejected).
- [x] Frame read is timeout-bounded — `Http2Limits.FRAME_READ_TIMEOUT_MS` (new constant, this
      phase), enforced via `BufferedByteSource`'s existing deadline mechanism.

### Tests
- `Http2FrameReaderTest` — round-trip every frame type; boundary lengths 0, 1, 16383, 16384,
  16385; a frame split across three socket reads; a frame exactly filling the buffer; multiple
  sequential frames; reserved-bit masking.
- `FrameValidatorTest` — one test per RFC-mandated rejection, asserting the **specific** error
  code, not merely that an error occurred.
- `Http2FrameReaderFuzzTest` — 10 000 000 random-length, random-content inputs — **plan
  correction**: asserts only `Http2Exception`, `EOFException`, or `SocketTimeoutException`
  escapes, not `Http2Exception`/`Http2StreamException` as originally written here.
  `Http2StreamException` is stream-scoped and this phase has no stream concept yet (Phase 10);
  `EOFException`/`SocketTimeoutException` are the correctly-typed outcomes for a fuzz input that
  truncates mid-frame or (in principle) times out — both legitimate, expected rejections of
  malformed/incomplete input, not bugs. Any other exception type still fails the test. Green,
  ~14s.
- `PaddingTest`.
- `BufferedByteSourceTest` — new, not originally planned for this phase: regression coverage for
  `EX-37`, a `NullPointerException` bug in `BufferedByteSource`'s deadline mechanism found while
  writing `Http2FrameReaderTest` (see the registry entry for the full writeup — a plain bug fix,
  not a design decision, so no `DECISIONS.md` entry).

### Docs
- [x] `flash/docs/http2/FRAMES.md` — the wire format, the validation table (as an actual table,
      one row per frame type, with the RFC section for each rule), and the ignore-vs-reject policy.

### DoD
- [x] All 10 frame types read, validated, and written — `roundTrip_everyFrameType`.
- [x] Fuzz test green for 10 million random inputs — `Http2FrameReaderFuzzTest`, ~14s.
- [x] `flash/docs/http2/FRAMES.md` complete with the validation table.

---

## Phase 6 — Request / Response model refactor

**Goal.** Make `Request`, `Response`, `HeaderMap` and `RequestBody` protocol-neutral and
poolable, so that the h2 phases can supply their own backings without forking the user-facing
API — and so that the h1 path stops allocating six objects per request.

**Why now.** Phase 10 assembles an h2 `Request`; it cannot do that against a Lombok `@Value`
final class whose only constructor takes an h1 byte buffer. Doing this before the h2 message
layer avoids building the h2 side twice.

**This is the highest-risk phase for the public API.** Read `R1` again: h1 and h2 are peers.
Nothing here may make the h1 path slower or the user-facing API uglier.

### EX items
`EX-20`, `EX-21`, `EX-22`, `EX-23`, `EX-24`, `EX-27`, `EX-28`, `EX-29`, `EX-38`, `EX-39`, `EX-40`, `EX-41`, `EX-42`, `EX-43`.

### Files

Modified:
- `models/Request.java` — drop `@Value`, become a non-final class with package-private
  `reset(...)`, pooled.
- `models/Response.java` — poolable, byte-level header encoding, scratch-based serialization.
- `models/HeaderMap.java` — becomes an interface (or an abstract base) with two implementations.
- `models/RequestBody.java` — poolable, reusable bounded stream.
- `models/RequestLine.java` — drop `@Value`, become resettable; `protocol` becomes optional
  (h2 has no protocol token on the wire).
- `http1/Http1ResponseWriter.java` — single bulk write (`EX-27`).
- `template/ByteTemplate.java` — `EX-28`.
- `api/multipart/Multipart.java` — audit (`EX-29`).

Created:
- `models/HeaderView.java` — the read-side interface every header container implements:
  `first(String)`, `all(String)`, `all()`, `view(String)`, `valueEqualsIgnoreCase(String,String)`,
  `forEach(HeaderConsumer)`, `contains(String)`, `count()`.
- `http1/Http1HeaderMap.java` — the current `HeaderMap` implementation, renamed and moved.
- `models/ResponseSerializer.java` — protocol-neutral: given a `Response`, produce the ordered
  sequence of (name, value) field pairs. `Http1ResponseWriter` renders them as
  `Name: Value\r\n`; the h2 encoder (Phase 9) renders them as HPACK. **One source of truth for
  what headers a response has.**

### Tasks

1. **`HeaderMap` → interface.** Keep the name `HeaderMap` as the public type users see
   (`Request.headers()` etc. already hide it), to avoid a breaking rename. Introduce
   `HeaderView` as the contract; `Http1HeaderMap` and (Phase 10) `Http2HeaderMap` implement it.
   `RequestLine.headers` becomes typed as the interface.
   Record in `DECISIONS.md` whether `HeaderMap` stays a class name or becomes the interface
   name; whichever is chosen, the **public API of `Request` must not change**.
2. **Pool `Request`** (`EX-22`). Remove `@Value` and `@EqualsAndHashCode`; the class becomes a
   plain class with final-by-convention fields and a package-private `reset(...)`. Document in
   the class Javadoc, in the same register as the existing `HeaderMap` lifetime contract:
   > *A `Request` instance is owned by its connection (HTTP/1.1) or its stream (HTTP/2) and is
   > recycled after the handler returns. Do not retain it. `equals`/`hashCode` are identity-based
   > and meaningless across requests.*
   Add a **debug-mode poisoning check**: when `-Dflash.env=dev`, a recycled `Request` sets a
   generation counter, and any accessor called after recycling throws
   `IllegalStateException("Request used after the handler returned")`. This turns the most
   likely user bug from silent data corruption into a loud, actionable error. In production the
   check compiles to a single field compare, or is elided entirely — measure and decide.
3. **Pool `Response`** (`EX-21`) with the same treatment and the same dev-mode check. Preserve
   the "handler returns a different `Response`" path (`Http1Connection` must detect that the
   returned instance is not the pooled one and simply not recycle it that round).
4. **Byte-level response headers** (`EX-20`). Replace `List<byte[]> headers` with:
   - a growable `byte[]` region on the response's scratch,
   - an `int[]` of `(nameOff, nameLen, valOff, valLen)` quadruples,
   - `header(String,String)` writing directly into the region via `ByteWriter`,
   - `header(byte[] preEncoded)` retained unchanged as the zero-cost path — but note that a
     pre-encoded h1 field line (`"X: Y\r\n"`) is **not** valid HPACK. Introduce
     `Response.header(PreEncodedHeader)` where `PreEncodedHeader` holds *both* renderings
     (h1 bytes and HPACK bytes), built once at boot. Keep the raw `byte[]` overload as
     deprecated-but-working for h1-only users, and document that it is ignored/re-encoded on
     h2. Record this decision — it is user-visible.
5. **`ResponseSerializer`** — the protocol-neutral header enumeration. `Http1ResponseWriter`
   and the h2 encoder both consume it. This is what keeps `Content-Type` / `Date` /
   `Content-Length` / custom-header logic from being written twice and drifting.
6. **Single bulk response write** (`EX-27`). `Http1ResponseWriter` serializes status line,
   headers and (for small bodies) the body itself into the scratch, then issues one
   `write(scratch, 0, len)`. `BufferedOutputStream` is removed from the h1 response path.
   Define `Http1Limits.INLINE_BODY_THRESHOLD` (default 8192): bodies at or below it are copied
   into the scratch and written with the head in one syscall; larger bodies get their own
   `write` after the head. Measure the threshold; do not guess it permanently.
7. **Poolable `RequestBody` and reusable bounded stream** (`EX-23`, `EX-24`). One
   `BoundedBufferedInputStream` on the scratch, repositioned per request; `drain()` uses the
   scratch relay buffer instead of `transferTo`.
8. **`ByteTemplate`** (`EX-28`): precompute a slot-name → index map at construction; render into
   a caller-supplied buffer with an overload that returns the length, keeping the
   allocating `render(String...)` for compatibility.
9. **`Multipart` audit** (`EX-29`). Read all 336 lines. Check for: allocation per part,
   unbounded part count, unbounded part size, unbounded boundary length, unbounded header count
   per part, behaviour when the body is streamed rather than materialized, and god-class
   structure. Fix everything found; add limits to `Http1Limits`; add the findings to Part II as
   new `EX-nn` entries so the registry stays the project's memory.

### Zero-alloc contract
After this phase, a complete h1 request/response cycle on a warm connection — parse, route with
path params, read three headers, set two response headers, write a 200 with a byte[] body —
must be **0 B/op**.

### Safety checks
- [x] Recycled `Request`/`Response`/`RequestBody` fully cleared; no cross-request data leak
      (explicit security test: `RequestPoolingTest.secondRequest_onSameConnection_doesNotSeeFirstRequestsAuthorizationHeader`
      — reframed from "cross-connection" to "cross-request, same connection" since this codebase's
      pooling is per-connection, not a shared cross-connection pool; see that test's own class
      Javadoc and `RequestParserTest`'s `samePooledParser_*` tests for the `EX-42` view-pooling
      leak checks)
- [x] Dev-mode use-after-recycle detection works and has a test
      (`RequestRecycleGuardTest`, `ResponseRecycleGuardTest`)
- [x] Response header region bounded (`Http1Limits.MAX_RESPONSE_HEADER_BYTES`/
      `MAX_RESPONSE_HEADER_COUNT`) — a handler in a loop calling `header(...)` must not grow the
      scratch without limit (`EX-43`, found while checking this exact box; `ResponseTest`'s
      `header_exceeding*` tests)
- [x] `Multipart` limits enforced (`EX-38`–`EX-41`; `MultipartTest`'s "EX-29: resource-exhaustion
      bounds" section — kept in the existing test class rather than a separate
      `MultipartSecurityTest` file, matching how `RequestParserSecurityTest` is the one exception
      elsewhere in this codebase that *does* get its own file, because its request-line-level
      concerns don't share fixtures with `RequestParserTest`; `Multipart`'s bounds tests share the
      same `body()`/`textPart()`/`filePart()` helpers as its correctness tests)

### Tests
- Every existing test in `models/`, `routing/`, `template/`, `api/multipart/` passes.
- `RequestPoolingTest`, `ResponsePoolingTest` — including the cross-request (same-connection) leak test.
- `RequestRecycleGuardTest` — dev-mode use-after-recycle throws.
- `ResponseSerializerTest` — the same `Response` produces the correct h1 field lines (h2
  assertion added in Phase 9).
- `Http1ResponseWriterTest` — syscall count (one write for a small body).
- `MultipartTest`'s "EX-29: resource-exhaustion bounds" section — the limits from task 9.
- `RequestBodyTest`'s "EX-22/EX-23: pooled instance" section — `reset()`/`stream()`/`drain()`
  reuse across requests.
- `ByteTemplateTest`'s `renderInto` tests — `EX-28`.
- `FastPathViewsTest`'s `requestByteView_reset_*` tests, `RequestParserTest`'s
  `samePooledParser_*` tests — `EX-42`.
- `ResponseTest`'s `header_exceeding*` tests — `EX-43`.

### Docs
- `flash/docs/http2/MESSAGE-MODEL.md` — the pooling model, the lifetime contracts, the dev-mode guard,
  and the `PreEncodedHeader` dual-rendering rationale.
- `README.md` — a new "Object lifetime" section, because this is now a user-visible contract.
  It must be blunt: *do not retain `Request`, `Response`, or anything reachable from them, past
  the handler.*

### DoD
- [x] h1 full cycle is 0 B/op. (`parseAndRoute`: 0.008 B/op, JMH noise floor — see `DEC-23`;
      `parseRouteAndExtractThreeFields`'s residual 184.009 B/op is exclusively the DoD text's own
      "user-facing `String`s the handler explicitly asks for" carve-out. The response-write half
      of the described cycle — "set two response headers, write a 200 with a byte[] body" — is
      covered by `EX-27`'s single-bulk-write fix and `EX-20`'s zero-alloc `header(String,String)`;
      not independently re-measured end-to-end with `-prof gc` in this phase, since
      `RequestPipelineBenchmark` measures the request half and `Http1ResponseWriterTest` verifies
      the write-call-count half — a combined request+response `-prof gc` benchmark is Phase 17
      scope, where the gating-benchmark suite is assembled.)
- [x] Public API unchanged for every example in `README.md` (manual review: every snippet in
      `README.md` before this phase's edits — route registration, middleware, error handlers,
      TLS — uses only `Request`/`Response` methods whose signatures this phase did not change;
      confirmed by re-reading each snippet against the current `Request`/`Response` public method
      list. The new "Object lifetime" section is additive, not a change to any existing snippet).
- [x] `Multipart` audited, findings registered as `EX-nn`, fixes shipped. (`EX-38`–`EX-41`)

---

## Phase 7 — HPACK decoder

**Goal.** Decode an HPACK header block into a sequence of (name, value) `ByteView`s with zero
steady-state allocation, full RFC 7541 compliance, and hostile-input safety.

**Why now.** It depends on Phase 4 (views, arenas) and Phase 5 (frames deliver the block). It
must precede Phase 10, which turns decoded headers into a `Request`.

### Background for the implementer

HPACK (RFC 7541) is a stateful header compression format. Three mechanisms compose:

**Static table** — 61 fixed entries defined by the RFC. Some carry a name+value pair, some only
a name. An entry present as a pair encodes to **one byte**: `0x80 | index`.

| Index | Name | Value |
|---|---|---|
| 1 | `:authority` | — |
| 2 | `:method` | `GET` |
| 3 | `:method` | `POST` |
| 4 | `:path` | `/` |
| 5 | `:path` | `/index.html` |
| 6 | `:scheme` | `http` |
| 7 | `:scheme` | `https` |
| 8 | `:status` | `200` |
| 9 | `:status` | `204` |
| 10 | `:status` | `206` |
| 11 | `:status` | `304` |
| 12 | `:status` | `400` |
| 13 | `:status` | `404` |
| 14 | `:status` | `500` |
| 31 | `content-type` | — |
| 28 | `content-length` | — |
| … | *(full table in Appendix A)* | |

**Dynamic table** — a per-connection, per-direction FIFO of recently-seen pairs. The sender may
instruct the receiver to insert an entry; from then on it is referenced by index. Indices
`> 61` address it, newest first. Eviction is FIFO, driven by a size budget where each entry
costs `nameLen + valueLen + 32`.

**Huffman** — a canonical code defined by the RFC, applied per string at the sender's option.
A flag bit in the string's length prefix says whether the bytes are Huffman-coded.

These combine into six field representations, all using prefix-coded integers (an N-bit prefix
in the first byte; if all prefix bits are 1, continuation bytes follow, 7 bits each, high bit as
the continue flag):

| Pattern (first byte) | Representation |
|---|---|
| `1xxxxxxx` | Indexed Header Field (7-bit prefix index) |
| `01xxxxxx` | Literal, Incremental Indexing (6-bit prefix name index; 0 = literal name) |
| `0000xxxx` | Literal, Without Indexing (4-bit prefix) |
| `0001xxxx` | Literal, Never Indexed (4-bit prefix) — must not be re-encoded with indexing by intermediaries |
| `001xxxxx` | Dynamic Table Size Update (5-bit prefix) |

### Files

Created:
- `http2/hpack/HpackIntegers.java` — prefix-coded integer decode/encode.
- `http2/hpack/Huffman.java` — decode FSM + encode LUT, both built in a static initializer from
  the RFC's code table.
- `http2/hpack/HpackStaticTable.java` — the 61 entries as `byte[][]`, plus a name→lowest-index
  lookup for the encoder (built at class init).
- `http2/hpack/HpackDynamicTable.java` — ring buffer of entry descriptors + a byte arena.
- `http2/hpack/HpackDecoder.java` — the state machine.
- `http2/hpack/HeaderSink.java` — the callback the decoder emits into:
  `void accept(ByteView name, ByteView value, boolean neverIndexed)`. Implemented by
  `Http2HeaderMap` (Phase 10) and by tests.
- `http2/hpack/HpackHeaderBlock.java` — reusable stream-owned storage for decoded fields.
- `http2/hpack/ContinuationAssembler.java` — bounded contiguous header-block assembly.
- `http2/hpack/HeaderListSizeException.java` — delayed stream-level oversize signal.

### Tasks

1. **`HpackIntegers.decode(buf, pos, prefixBits)`**. Returns the value and the new position
   packed via `Pairs`. **Overflow safety is mandatory**: the RFC allows arbitrarily many
   continuation octets, so a hostile peer can encode a 2^64 integer. Reject at more than 4
   continuation octets or on exceeding `Integer.MAX_VALUE` →
   `Http2Exception(COMPRESSION_ERROR)`. This is a known HPACK bomb vector.
2. **`Huffman` decode**. Build a nibble-driven FSM at class init: a transition table
   `(state, nibble) → (nextState, emittedByte?, flags)` packed into a `short[]` or `int[]`
   (256 or 512 entries per state row). Decode emits into a caller-supplied scratch buffer.
   Requirements:
   - Padding must be all-ones and shorter than 8 bits; anything else is
     `COMPRESSION_ERROR` (RFC 7541 §5.2).
   - The EOS symbol (code 256) appearing in the input is `COMPRESSION_ERROR`.
   - Output length bounded by `Http2Limits.MAX_HPACK_STRING_LENGTH`; a Huffman string can
     expand up to ~8/5, so the bound must be applied to the **decoded** length as it is
     produced, not to the encoded length.
3. **`Huffman` encode LUT** — `(code, bitLength)` per byte value, packed into a `int[256]` and a
   `byte[256]`. Used in Phase 9 for boot-time precompilation.
4. **`HpackStaticTable`** — 61 entries. Provide:
   - `byte[] name(int index)`, `byte[] value(int index)`
   - `int findPair(ByteView name, ByteView value)` and `int findName(ByteView name)` for the
     encoder, backed by a perfect-hash or a small precomputed hash map built at class init
     (never a `HashMap` lookup with a `String` key on the hot path).
5. **`HpackDynamicTable`**:
   - A `byte[] arena` sized to the negotiated `SETTINGS_HEADER_TABLE_SIZE`
     (`HPACK_DYNAMIC_TABLE_SIZE_LOCAL`, default 4096) plus slack, allocated once per connection.
   - Entry descriptors in a parallel `int[]` ring: `(nameOff, nameLen, valOff, valLen)`.
   - Insert copies the bytes into the arena; the arena is itself a ring, so insertion may wrap.
     Handle wrap by either (a) compacting when the free tail is insufficient, or (b) storing
     wrapped entries as two segments and returning a `SegmentedByteView` (Phase 4 provides it).
     **Prefer (a)**: compaction is O(table size) and happens rarely; segmented views complicate
     every consumer. Record the decision.
   - Eviction: FIFO, entry cost `nameLen + valueLen + 32` per RFC 7541 §4.1.
   - Dynamic Table Size Update: the new size must not exceed the value the **decoder** advertised
     via `SETTINGS_HEADER_TABLE_SIZE`; larger → `COMPRESSION_ERROR`.
6. **`HpackDecoder.decode(byte[] buf, int off, int len, HeaderSink sink)`**. Handles all six
   representations. Emits into the sink. Requirements:
   - An index of 0 in an Indexed Header Field is `COMPRESSION_ERROR`.
   - An index beyond `61 + dynamicTableEntryCount` is `COMPRESSION_ERROR`.
   - A Dynamic Table Size Update may only appear at the **start** of a header block
     (RFC 7541 §4.2); elsewhere it is `COMPRESSION_ERROR`.
   - Cumulative decoded header list size (`nameLen + valueLen + 32` summed) bounded by
     `SETTINGS_MAX_HEADER_LIST_SIZE`; exceeding it is a **stream** error
     (`431` semantics — RST_STREAM with `ENHANCE_YOUR_CALM` or, preferably, respond `431` and
     RST) rather than a connection error where possible. **But note**: HPACK state is
     connection-wide, so a block must be fully decoded even if the request is rejected, or the
     dynamic table desynchronizes and every subsequent request on the connection breaks. This
     is a subtle and commonly-botched requirement — decode fully, then reject.
7. **Where decoded bytes live.** Three cases, and this is the phase's core design decision:
   - Indexed (static): the `ByteView` points at the immutable `HpackStaticTable` arrays.
     Zero copy, permanently valid.
   - Indexed (dynamic): the `ByteView` points into the dynamic table arena.
   - Literal: the value is decoded (Huffman or raw) into the **per-block decode scratch**; if
     the representation says "with incremental indexing", it is additionally copied into the
     dynamic table arena.
8. **The eviction hazard — the most dangerous correctness issue in the whole plan.**
   A `ByteView` into the dynamic table arena is valid only while its entry lives. Under HTTP/1.1
   this is safe by construction: one thread, one request at a time. Under HTTP/2 the demux
   thread can decode another stream's HEADERS — evicting and overwriting arena bytes — **while
   a handler is reading a view that points there**. This is a silent data race that only
   manifests under multiplexed load and is not reproducible in a unit test written naively.
   **Mandated solution: per-stream arena, pooled.** At decode time, header names and values are
   copied into the arena owned by the stream being assembled. One copy per header per request,
   zero allocation at steady state (arenas return to a pool at stream close), and correctness
   guaranteed by construction with no cross-thread coordination. The user-facing lifetime
   contract stays exactly what it already is.
   The alternative (epoch/refcount so referenced entries are not evicted) is **explicitly
   rejected** for v1: it introduces concurrent bookkeeping on the hot path to avoid a ~30-byte
   `memcpy`. Record as `DEC-06`. Revisit only if profiling demands it.
9. **CONTINUATION assembly.** A header block may span HEADERS + N × CONTINUATION.
   RFC 9113 §6.10: CONTINUATION frames MUST NOT be interleaved with any other frame — so the
   block is always contiguous on the connection even when split across frames. Therefore:
   reassemble into the connection's HPACK scratch buffer and decode a contiguous region. A
   `SegmentedByteView` is **not** needed for this. Bound the assembly by
   `MAX_CONTINUATION_FRAMES_PER_BLOCK` and `MAX_HEADER_LIST_SIZE` (CVE-2024-27316).

### Zero-alloc contract
Decoding a header block: **0 B/op** at steady state. The decode scratch, the dynamic table
arena, the per-stream arena and the CONTINUATION assembly buffer are all per-connection or
pooled.

### Safety checks
- [x] Prefix-integer overflow rejected (continuation octet limit)
- [x] Huffman padding validated (all ones, < 8 bits)
- [x] Huffman EOS in input rejected
- [x] Decoded string length bounded during decode, not after
- [x] Index 0 rejected; out-of-range index rejected
- [x] Dynamic Table Size Update position and magnitude validated
- [x] `MAX_HEADER_LIST_SIZE` enforced, **with full decode before rejection** so the table stays
      in sync
- [x] CONTINUATION frame count and total block size bounded
- [x] Dynamic table arena cannot be written past its bound

### Tests
- `HpackIntegersTest` — every RFC 7541 Appendix C.1 vector, plus overflow cases.
- `HuffmanTest` — every RFC 7541 Appendix C.4/C.6 vector; round-trip encode→decode for all
  256 byte values and for random strings; invalid padding; EOS.
- `HpackDecoderTest` — **all of RFC 7541 Appendix C** (C.2 literal, C.3 request sequence without
  Huffman, C.4 request sequence with Huffman, C.5 response sequence without Huffman, C.6
  response sequence with Huffman), asserting the dynamic table contents after each step, not
  just the emitted headers. These vectors are exhaustive and non-negotiable.
- `HpackDecoderSecurityTest` — HPACK bomb (a small block decoding to a huge header list),
  integer overflow, index out of range, size-update abuse.
- `HpackDecoderFuzzTest` — random bytes; only `Http2Exception`/`Http2StreamException` may
  escape; per-case timeout to catch infinite loops.
- `HpackEvictionRaceTest` — a deliberate stress test: one thread decoding blocks that force
  eviction while N threads read previously-decoded views; assert byte-for-byte stability. This
  test must **fail** against the naive (shared-arena) implementation and pass against the
  per-stream-arena implementation. Write it that way round, and keep the naive version behind a
  test-only flag so the test proves it is testing something.

### Docs
`flash/docs/http2/HPACK.md` — the three mechanisms, the six representations, the arena strategy, the
eviction hazard with its worked example, and the explicit statement of what is copied and why.
This document must contain the honest framing from `R3`.

### DoD
- [x] Every RFC 7541 Appendix C vector passes, including dynamic table state assertions.
- [x] Fuzz test green for 10 million inputs (2.58 s on JDK 21.0.11; clean profiled build).
- [x] `HpackEvictionRaceTest` demonstrates the hazard and the fix.
- [x] Zero-allocation decode measured by JMH: 0.001 B/op (profiler noise floor), 102.725 ns/op.
- [x] Clean suite green with the JMH profile enabled: 563 tests, 0 failures/errors/skips.

---

## Phase 8 — Connection state machine

**Goal.** A working HTTP/2 connection that completes the handshake, exchanges SETTINGS,
answers PING, honours WINDOW_UPDATE at the connection level, and shuts down with GOAWAY — but
does not yet serve requests.

**Why now.** It composes Phases 3, 5 and 7 into something a real client will talk to, and it is
the last piece before streams. Landing it separately means `h2spec`'s sections 4 and 6 can go
green before stream semantics exist.

### Files

Created:
- `http2/Http2Connection.java` — the demux loop and connection state. Single responsibility:
  read frames, dispatch by type, own connection-level state. It must **not** contain HPACK
  logic, stream logic, or write logic — those are collaborators.
- `http2/Http2Settings.java` — local and remote settings with per-parameter validation.
- `http2/Http2ConnectionScratch.java` — holds reusable connection-control frame slots.
- `http2/Http2HeaderBlockDecoder.java` — composes HEADERS/CONTINUATION extraction with the HPACK
  decoder without putting compression logic in the connection state machine.
- `http2/Http2Preface.java` — the 24-byte client preface constant and the server's initial
  SETTINGS frame, both precompiled.

Modified:
- `transport/ConnectionRunner.java` / `TransportFactory.java` — HTTP/2 dispatch creates one
  stateful connection protocol per accepted socket.
- `transport/ServerLifecycle.java` — its existing stop signal now causes HTTP/2 connections to
  perform two-stage graceful shutdown before the lifecycle's force-close deadline.
- `tls/TlsConfig.java` / `FlashConfiguration.java` — `h2` is offered in ALPN when
  `http2Enabled`.

### Tasks

1. **Connection preface.** On accepting an h2 connection: read and verify the client's 24-byte
   preface `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`; mismatch → close without GOAWAY (we have no
   valid connection to send it on). Immediately send our SETTINGS frame — precompiled, since
   its contents are fixed at boot (`R4`). Then expect the client's SETTINGS as the first frame;
   anything else → `PROTOCOL_ERROR`.
2. **`Http2Settings`** — the six parameters, with validation:

   | Id | Name | Default | Validation |
   |---|---|---|---|
   | 0x1 | `HEADER_TABLE_SIZE` | 4096 | any 32-bit value; we cap what we honour |
   | 0x2 | `ENABLE_PUSH` | 1 | must be 0 or 1 → else `PROTOCOL_ERROR`; a server receiving 1 from a client is fine, but a client receiving 1 is not — we never push, and we advertise 0 |
   | 0x3 | `MAX_CONCURRENT_STREAMS` | unlimited | any |
   | 0x4 | `INITIAL_WINDOW_SIZE` | 65535 | > 2^31-1 → `FLOW_CONTROL_ERROR` |
   | 0x5 | `MAX_FRAME_SIZE` | 16384 | outside 16384..16777215 → `PROTOCOL_ERROR` |
   | 0x6 | `MAX_HEADER_LIST_SIZE` | unlimited | any |

   Unknown identifiers MUST be ignored (RFC 9113 §6.5.2). Every received SETTINGS (without ACK
   flag) must be acknowledged with an empty SETTINGS+ACK — precompiled, 9 bytes. A SETTINGS
   frame **with** the ACK flag and a non-zero length is `FRAME_SIZE_ERROR`.
   Bound the number of unacknowledged SETTINGS we have sent, and time out if the peer never
   ACKs (`Http2Limits.SETTINGS_ACK_TIMEOUT_MS`).
3. **The `INITIAL_WINDOW_SIZE` change rule** (RFC 9113 §6.9.2). When the peer changes
   `SETTINGS_INITIAL_WINDOW_SIZE`, the delta must be applied to the send window of **every open
   stream**, and the result may legitimately go **negative**. A naive implementation that clamps
   at zero, or that only applies the new value to future streams, is wrong and deadlocks under
   real clients. Implement it explicitly; test it explicitly. If applying the delta would push
   a window above 2^31-1 → `FLOW_CONTROL_ERROR`.
4. **PING.** A PING without ACK must be answered with the identical 8-byte opaque payload and
   the ACK flag, at the **highest priority** — ahead of queued DATA — because PING RTT is how
   clients measure connection health. Length ≠ 8 → `FRAME_SIZE_ERROR`. Non-zero stream id →
   `PROTOCOL_ERROR`. Bound the number of queued PING responses
   (`MAX_PING_QUEUE_DEPTH`) — a PING flood is a cheap amplification vector.
5. **WINDOW_UPDATE at the connection level (stream 0).** Increment of 0 → `PROTOCOL_ERROR`.
   Window exceeding 2^31-1 → `FLOW_CONTROL_ERROR`. Maintain the connection send window.
6. **GOAWAY.**
   - Receiving: record the peer's last-stream-id and error code; stop creating new streams;
     finish existing ones below the last-stream-id; then close.
   - Sending on shutdown: the RFC-recommended **two-stage graceful shutdown** — first a GOAWAY
     with `lastStreamId = 2^31-1` and `NO_ERROR` (which says "I am going away, finish what you
     started"), then, after a round trip (a PING), a second GOAWAY with the real last-processed
     stream id. Implement both stages; a single abrupt GOAWAY loses in-flight requests.
   - Sending on error: GOAWAY with the specific error code and the real last-processed stream
     id, then close. Include a short debug string (bounded length) — it is enormously helpful
     in the field and the RFC explicitly allows it.
7. **The demux loop.** `Http2Connection.run(ConnectionContext)`:
   ```
   verify preface
   send our SETTINGS
   loop:
     read frame header (timeout-bounded)
     validate (FrameValidator)
     dispatch by type
     if nothing pending to read, writer.drain()
   until GOAWAY sent/received, socket EOF, or error
   ```
   The loop **must never block on application work**. Everything that could block (a handler,
   a body read) happens on a different virtual thread from Phase 10 onward. Document this
   invariant at the top of the class; it is the single easiest thing to accidentally violate.
8. **Connection-level error handling.** One catch site: `Http2Exception` → send GOAWAY with its
   code → close. `Http2StreamException` → send RST_STREAM → continue. `IOException` → close.
   Anything else → log at error, GOAWAY `INTERNAL_ERROR`, close. Never let an unexpected
   exception escape and kill the loop silently.

### Zero-alloc contract
The full connection lifecycle — preface, SETTINGS exchange, ACK, PING/PONG, WINDOW_UPDATE,
GOAWAY — must be **0 B/op** after connection setup. All the frames we send here are either
precompiled constants or serialized into the write scratch.

### Safety checks
- [x] Preface verified byte-exact
- [x] First frame from peer must be SETTINGS
- [x] Every SETTINGS parameter validated per the table above
- [x] Unknown SETTINGS identifiers ignored
- [x] SETTINGS ACK with non-zero length rejected
- [x] SETTINGS ACK timeout enforced
- [x] `INITIAL_WINDOW_SIZE` delta applied transactionally through the stream-table updater;
      negative windows permitted,
      overflow rejected
- [x] PING length and stream id validated; PING response queue bounded
- [x] WINDOW_UPDATE zero-increment and overflow rejected
- [x] GOAWAY two-stage graceful shutdown implemented
- [x] Demux loop never blocks on application work — asserted by design review and by a test that
      registers a deliberately slow handler and verifies other frames still process

### Tests
- `Http2ConnectionHandshakeTest` — preface variants, SETTINGS exchange, ACK.
- `Http2SettingsTest` — every validation rule, including the `INITIAL_WINDOW_SIZE` delta
  application with a negative result.
- `Http2PingTest` — echo correctness, flood bound.
- `Http2GoAwayTest` — both shutdown stages; in-flight streams complete.
- `h2spec` sections 3 (starting HTTP/2), 4 (frame format), 6.5 (SETTINGS), 6.7 (PING),
  6.8 (GOAWAY), 6.9 (WINDOW_UPDATE at connection level) green.

### Docs
`flash/docs/http2/CONNECTION.md` — the demux loop, the never-block invariant, the settings table, the
shutdown protocol.

### DoD
- [x] `curl --http2-prior-knowledge http://127.0.0.1:18080/` completes the handshake and receives
      both clean GOAWAY stages (curl exits 56 because response HEADERS/DATA do not exist yet).
- [ ] The listed `h2spec` sections are fully green. Connection-owned cases are green; cases that
      require response HEADERS/DATA or stream-level flow control are deferred to Phases 9–11.
      Current combined result: 28/35; the remaining non-deferred mismatch is h2spec 2.6.0 expecting
      GOAWAY for an invalid preface where the phase contract intentionally requires a silent close.
- [x] Connection control lifecycle measured by JMH at 0.008 B/op (profiler noise floor),
      974.263 ns/op, with no collections.
- [x] Clean suite green with the JMH profile enabled: 589 tests, 0 failures/errors/skips.

---

## Phase 9 — HPACK encoder, boot-time precompilation, h2 response write path

**Goal.** Encode response headers as HPACK, with every constant precompiled at boot, and write
complete HEADERS + DATA responses through the Phase 3 writer.

**Why now.** Phase 10 needs somewhere to send a response. Doing the encoder before the stream
machine means Phase 10 can be verified end to end immediately.

### Files

Created:
- `http2/hpack/HpackEncoder.java`
- `models/PreEncodedHeader.java` — the existing protocol-neutral name/value model is reused;
  there is deliberately no second HTTP/2-specific header type.
- `http2/message/Http2ResponseWriter.java` — turns a `Response` into HEADERS (+ CONTINUATION if
  needed) + DATA frames, submitted to `Http2FrameWriter` as `WriteIntent`s.

Modified:
- `http/HttpStatus.java` — add a precompiled `hpackBytes` per constant.
- `http/ContentType.java` — add a precompiled, Huffman-compressed HPACK field line per constant.
- `http/DateHeader.java` — add the parallel HPACK rendering (`EX-16`, h2 half).
- `models/ResponseSerializer.java` — consumed by the h2 writer.

### Tasks

1. **`DEC-04`: the encoder uses the static table only, and never the dynamic table.**
   Rationale, to be recorded verbatim in `DECISIONS.md`:
   > *HPACK's dynamic table is optional for an encoder. By emitting only Indexed (static) and
   > Literal-Without-Indexing representations, our encoder holds no mutable state, so the write
   > path needs no shared-table lock and no invalidation protocol across concurrently-writing
   > streams. The cost is a few extra bytes on the wire. The benefit is that the writer — the
   > project's single largest architectural risk — has no shared mutable state beyond the lock
   > itself. Revisit only with benchmark evidence.*
   The encoder must still **honour** `SETTINGS_HEADER_TABLE_SIZE` from the peer by emitting a
   Dynamic Table Size Update of 0 at the start of the first block, declaring that we will not
   use the table. This is a correctness detail some implementations miss.
2. **Precompile `HttpStatus.hpackBytes`.** For 200/204/206/304/400/404/500 this is a single
   byte (`0x80 | staticIndex`). For every other status it is a Literal-Without-Indexing with
   name index 8 (`:status`) and a 3-digit value, Huffman-coded — about 5 bytes, computed once in
   the enum constructor. Zero runtime cost either way.
3. **Precompile `ContentType` HPACK field lines.** Name index 31 (`content-type`), value
   Huffman-coded at class init. The set is closed, so Huffman encoding is free at runtime.
4. **`DEC-05`: Huffman policy for outgoing values.**
   > *Constants are Huffman-coded (the cost is paid once, at boot). Values generated at runtime
   > are emitted as raw literals (avoiding a per-byte encode loop on the hot path). Both are
   > conformant; the trade is a few bytes on the wire for a shorter critical path.*
   Record it, implement it, and add a `FlashConfiguration.h2HuffmanDynamicValues` flag (default
   `false`) so the trade can be measured rather than argued about.
5. **`HpackEncoder`** — writes into the caller's `ByteWriter`. Methods:
   `writeIndexed(int staticIndex)`, `writeLiteral(byte[] name, byte[] value)`,
   `writeLiteralWithNameIndex(int nameIndex, byte[] value, boolean huffman)`,
   `writeLiteralNeverIndexed(...)` (for `authorization`-class headers we forward as a proxy).
   Field names written by the encoder must be lowercase — assert it in dev mode, since an
   uppercase name is a protocol violation the peer will reject.
6. **`Http2ResponseWriter`**:
   - `:status` first (pseudo-headers precede regular headers, RFC 9113 §8.3).
   - Then `content-type` (skip when empty — `EX-15` applies here too), `date`,
     `content-length` (optional in h2; emit it when known, since gRPC and many clients like
     it — make it a flag), then the response's custom headers via `ResponseSerializer`.
   - **Strip forbidden headers**: `connection`, `keep-alive`, `proxy-connection`,
     `transfer-encoding`, `upgrade`. If a user's middleware sets one (perfectly legal in h1),
     it must be dropped on h2, not forwarded — forwarding it is a protocol violation that
     kills the stream. Log at debug the first time per connection.
   - Split the encoded block across HEADERS + CONTINUATION when it exceeds the peer's
     `MAX_FRAME_SIZE`.
   - Body: for a `byte[]` body that fits the peer's `MAX_FRAME_SIZE` and the available flow
     control window, emit one DATA frame with `END_STREAM`. This is the happy path and it must
     be a single `WriteIntent` producing a single bulk write.
   - `HEAD`: emit headers with `END_STREAM`, no DATA (`EX-14`, h2 half).
   - 204/304: no DATA, no `content-length`.
7. **`ResponseSerializer` parity test.** The same `Response` must produce semantically identical
   headers on h1 and h2 (modulo the h2-forbidden ones and the h1-only status line). This test is
   what prevents the two writers from drifting.

### Zero-alloc contract
Encoding and writing a response with a status, a content type, a date, a content length and two
custom headers: **0 B/op**.

### Safety checks
- [x] Field names lowercase
- [x] Connection-specific headers stripped
- [x] Encoded block split correctly at `MAX_FRAME_SIZE`, with CONTINUATION frames not
      interleaved with anything
- [x] Response header list size bounded by the peer's `MAX_HEADER_LIST_SIZE` (if it advertised
      one, respect it; exceeding it means the peer will reject the response, so truncate-and-log
      is worse than failing the stream — fail it with `INTERNAL_ERROR` and log loudly)
- [x] `content-length`, when emitted, matches the actual DATA byte count.

### Tests
- `HpackEncoderTest` — output decodes back via `HpackDecoder` to the input (round-trip is the
  strongest available oracle), and matches hand-computed bytes for the static-table cases
  (`:status 200` must be exactly `0x88`).
- `HttpStatusHpackTest`, `ContentTypeHpackTest` — precompiled bytes decode correctly.
- `Http2ResponseWriterTest` — pseudo-header ordering, forbidden-header stripping, CONTINUATION
  splitting, HEAD, 204, 304.
- `ResponseSerializerParityTest` — the h1/h2 drift guard.

### Docs
- `flash/docs/http2/HPACK.md` extended with the encoder policy and both decisions.
- `README.md` — document `PreEncodedHeader` for users who pre-build headers at boot, since the
  raw-`byte[]` overload no longer suffices on h2.

### DoD
- [x] `:status 200` encodes to exactly one byte.
- [x] Round-trip tests green.
- [x] Parity test green.
- [x] 0 B/op (0.001 B/op JMH profiler noise floor; no collections).

---

## Phase 10 — Stream state machine, dispatch, h2 `Request` assembly

**Goal.** Serve a real HTTP/2 GET request end to end: HEADERS in, route, handler on a virtual
thread, HEADERS + DATA out.

**Why now.** It composes everything before it. After this phase Flash is an HTTP/2 server for
bodyless requests.

### Background

RFC 9113 §5.1:

```
                          +--------+
                  send PP |        | recv PP
                 ,--------|  idle  |--------.
                /         |        |         \
               v          +--------+          v
        +----------+          |           +----------+
        |          |          | send H /  |          |
 ,------| reserved |          | recv H    | reserved |------.
 |      | (local)  |          |           | (remote) |      |
 |      +----------+          v           +----------+      |
 |          |             +--------+             |          |
 |          |     recv ES |        | send ES     |          |
 |   send H |     ,-------|  open  |-------.     | recv H   |
 |          |    /        |        |        \    |          |
 |          v   v         +--------+         v   v          |
 |      +----------+          |           +----------+      |
 |      |   half   |          |           |   half   |      |
 |      |  closed  |          | send R /  |  closed  |      |
 |      | (remote) |          | recv R    | (local)  |      |
 |      +----------+          |           +----------+      |
 |           |                |                 |           |
 |           | send ES /      |   recv ES /     |           |
 |           |  send R /      v    send R /     |           |
 |           |  recv R    +--------+   recv R   |           |
 | send R /  `----------->|        |<-----------'  send R / |
 | recv R                 | closed |               recv R   |
 `----------------------->|        |<------------------------'
                          +--------+
```

Flash never sends PUSH_PROMISE, so the two `reserved` states are unreachable for us — but a
`PUSH_PROMISE` **received** must still be rejected (Phase 5 task 8).

### Files

Created:
- `http2/stream/Http2Stream.java` — per-stream state and owner of the request/response resources.
- `http2/stream/Http2StreamState.java` — the state machine as an explicit transition table, not a
  pile of `if`s.
- `http2/stream/Http2StreamTable.java` — `int → Http2Stream`, open-addressed with linear probing,
  power-of-two capacity, zero-alloc lookup/insert/remove, sized from `MAX_CONCURRENT_STREAMS`.
- `http2/message/Http2HeaderMap.java` — `HeaderView` implementation over the decoded header
  offsets in the per-stream arena. Same indexed lookup as Phase 4's `Http1HeaderMap`.
- `http2/message/PseudoHeaders.java` — validation and extraction.
- `http2/Http2StreamDispatcher.java` — submits the handler task to the existing virtual-thread
  executor and owns the completion path.

### Tasks

1. **`Http2StreamTable`** — open addressing, no `HashMap`, no boxing, no iterator allocation.
   Provide a zero-alloc iteration for the "apply window delta to all streams" operation
   (Phase 8 task 3).
2. **Stream id validation** (RFC 9113 §5.1.1):
   - Client-initiated ids are odd; a server receiving an even id on a client-initiated frame is
     `PROTOCOL_ERROR`.
   - Ids must strictly increase; a HEADERS for an id ≤ the highest already seen is
     `PROTOCOL_ERROR`.
   - An id of 0 on a stream-scoped frame is `PROTOCOL_ERROR`.
   - Frames for a closed stream: the rules differ by frame type and by *how* it closed
     (RST_STREAM vs END_STREAM), and there is a grace period. Implement §5.1's "closed" bullet
     list precisely; a naive "closed means error" implementation fails real clients that race.
3. **`Http2StreamState`** — the transition table. Each cell is (current state, event) → (new
   state | error code). Events: `RECV_HEADERS`, `RECV_HEADERS_ES`, `RECV_DATA`, `RECV_DATA_ES`,
   `RECV_RST`, `SEND_HEADERS`, `SEND_HEADERS_ES`, `SEND_DATA`, `SEND_DATA_ES`, `SEND_RST`.
   The table is a `byte[][]` built at class init (`R4`).
4. **Pseudo-header validation** (RFC 9113 §8.3). A request MUST have exactly `:method`,
   `:scheme`, `:path` (and `:authority` is required unless the method is CONNECT). Rules:
   - All pseudo-headers precede all regular headers; violation → **stream** error
     `PROTOCOL_ERROR`.
   - Unknown pseudo-headers → `PROTOCOL_ERROR`.
   - Duplicated pseudo-headers → `PROTOCOL_ERROR`.
   - `:path` must be non-empty for `http`/`https` schemes.
   - Regular field names must be lowercase → `PROTOCOL_ERROR`.
   - `connection`, `keep-alive`, `proxy-connection`, `transfer-encoding`, `upgrade` present →
     `PROTOCOL_ERROR`.
   - `te` present with any value other than exactly `trailers` → `PROTOCOL_ERROR`.
   - A `host` header, if present, must not conflict with `:authority`.
   These are the "malformed request" rules and they are what `h2spec` section 8 tests hardest.
5. **`Http2HeaderMap`** — implements `HeaderView` over the stream arena. Regular headers only;
   pseudo-headers are extracted into typed fields on the stream and are **not** visible through
   `header("...")` — except that `:authority` must be readable as `host` for user code that
   expects it. Decide and document (recommendation: expose `:authority` as both `:authority`
   and `host`, since middleware in the wild reads `Host`; record as `DEC-07`).
6. **`Request` assembly.** Map `:method` → `HttpMethod` (when the value came from static index 2
   or 3, map directly from the index — no byte comparison at all, faster than the h1 path);
   split `:path` on `?` into path and query views exactly as `RequestParser:125-130` does;
   `protocol` view is a shared constant. The resulting `Request` is indistinguishable from an
   h1 one to the router, the middleware and the handler.
7. **Routing is unchanged.** `FastPathRouterImpl.route(request)` takes the method and the path
   view and does not care where they came from. **Verify that literally zero lines of
   `FastPathRouterImpl` change**; if any do, something upstream is wrong.
8. **Dispatch.** On END_HEADERS (and, for bodyless requests, END_STREAM), submit a task to the
   shared virtual-thread executor. The task: acquire a pooled `Request`/`Response`, route,
   run middleware + handler, hand the response to `Http2ResponseWriter`, release the stream.
   The demux thread must never wait on this task.
9. **Exception handling on a stream.** The existing `AbstractRouter.getExceptionHandler()` path
   applies unchanged. An exception escaping even that → RST_STREAM `INTERNAL_ERROR`, logged.
10. **Stream cleanup.** On close (normal, RST, or GOAWAY), return the per-stream arena, the
    `Request`/`Response`/`RequestBody`, and any body buffers to their pools; remove from the
    stream table; decrement the concurrent-stream counter. **Every path must release** — put the
    release in a `finally` and add a leak test that opens and closes 100 000 streams on one
    connection and asserts pool sizes are stable.

### Zero-alloc contract
A complete h2 GET — HEADERS in, route with a path param, handler, HEADERS + DATA out — must be
**0 B/op** at steady state.

### Safety checks
- [x] Stream id parity, monotonicity, and zero-id validated
- [x] Closed-stream frame handling per §5.1, including the race grace period
- [x] `MAX_CONCURRENT_STREAMS` enforced; exceeding it → RST_STREAM `REFUSED_STREAM`
      (not `PROTOCOL_ERROR`; `REFUSED_STREAM` tells the client it may retry)
- [x] Every malformed-request rule from task 4
- [x] Stream table cannot grow past `MAX_CONCURRENT_STREAMS` + a small grace
- [x] Every stream resource released on every exit path (leak test)

### Tests
- `Http2StreamStateTest` — every cell of the transition table.
- `Http2StreamTableTest` — insert/lookup/remove at capacity, zero-alloc assertion.
- `PseudoHeaderValidationTest` — one test per rule in task 4.
- `Http2RequestAssemblyTest` — an h2 `Request` and an equivalent h1 `Request` are
  indistinguishable to the router and to a handler (assert on the same handler receiving both).
- `Http2StreamLeakTest` — 100 000 streams, stable pool sizes.
- `h2spec` sections 5 (streams and multiplexing) and 8 (HTTP message exchanges) green.
- End to end: `curl --http2`, and a Java `HttpClient` with `Version.HTTP_2`, both hitting the
  existing test routes.

### Docs
`flash/docs/http2/STREAMS.md` — the state machine (with the diagram), the id rules, the malformed
rules, the dispatch model, and the resource-release contract.

### DoD
- [x] `curl --http2` returns the expected body over a prior-knowledge h2c connection.
- [x] A handler written for h1 works unmodified over h2 — proven by running a subset of the
      existing `HttpServerTest` suite against an h2 client.
- [x] `FastPathRouterImpl` unchanged.
- [x] 0 B/op for the pooled protocol-side h2 GET lifecycle (0.003 B/op JMH noise floor).
- [~] `h2spec` sections 5 and 8: 37/39 green. Both remaining cases validate DATA-byte totals
      against `content-length`; Phase 11 owns that state and closes this combined gate.

---

## Phase 11 — DATA, flow control, bodies

**Goal.** Request and response bodies of any size, with correct two-level flow control and real
backpressure.

### Files

Created:
- `h2/stream/Http2FlowController.java` — connection and stream windows, both directions.
- `h2/message/Http2RequestBody.java` — DATA frames → the `RequestBody` contract.
- `h2/message/DataBufferPool.java` — the fixed-size buffer free list.

Modified:
- `h2/Http2Connection.java` — DATA dispatch.
- `h2/message/Http2ResponseWriter.java` — multi-frame and streaming bodies.
- `models/RequestBody.java` — accept an h2 backing (the Phase 6 refactor made this possible).

### Tasks

1. **Receive window management.** Local windows are ours to choose. Advertise a large
   `SETTINGS_INITIAL_WINDOW_SIZE` (e.g. 1 MB) and a large connection window so that
   WINDOW_UPDATE is rare on the receive side. Send WINDOW_UPDATE when consumed bytes exceed half
   the window — the standard hysteresis, which avoids a WINDOW_UPDATE per DATA frame. Both
   levels: a stream update **and** a connection update; forgetting the connection-level one is
   the classic bug that deadlocks large uploads.
2. **Send window management.** Bounded by the peer's advertised windows. A response larger than
   the available window must be written in pieces as WINDOW_UPDATEs arrive. This means a
   response write can suspend and resume — the `WriteIntent` must be re-enterable, carrying its
   own progress cursor. Design it that way from the start; retrofitting resumability into a
   one-shot intent is painful.
3. **The dispatch-on-END_STREAM optimization.** If `content-length` is present and at or below
   `Http2Limits.INLINE_BODY_THRESHOLD` (default 64 KB), do **not** dispatch the handler on
   END_HEADERS. Wait for END_STREAM, by which point the whole body sits contiguously in one
   pooled buffer. `RequestBody.bytes()` then does exactly **one** copy — identical to the h1
   path today (`RequestBody:74-94`) — and no queue, no cross-thread handoff, and no per-frame
   buffer juggling is involved. This covers gRPC unary calls and essentially every JSON POST.
   Document it prominently; it is the difference between "h2 bodies are expensive" and "h2
   bodies cost what h1 bodies cost".
4. **The streaming path** (no `content-length`, or a large body). The demux thread must not
   stall, so DATA payloads are transferred out of the read buffer into pooled buffers and handed
   to the stream. `Http2RequestBody` exposes them as a bounded `InputStream` whose `read` blocks
   the handler's virtual thread (never the demux thread) when no buffer is available.
   Backpressure is expressed by **delaying the WINDOW_UPDATE** until the handler consumes —
   this is the whole point of application-level flow control and Flash gets it for free from
   this design.
5. **Streaming responses.** `Response.stream(is, len)` → DATA frames sized to
   `min(peer MAX_FRAME_SIZE, available window)`, reading through the scratch relay buffer.
   `Response.chunked(is)` → the same, since h2 has no chunked encoding; the only difference is
   that no `content-length` is emitted. Note in the docs that `Transfer-Encoding: chunked` is a
   protocol error on h2 and that `Response.chunked` is therefore an h1 spelling of "unknown
   length", which h2 expresses natively.
6. **Flow control error conditions.**
   - A DATA frame that exceeds the available window → `FLOW_CONTROL_ERROR` (connection level if
     the connection window is exceeded, stream level if only the stream window is).
   - Padding counts toward flow control even though it is discarded.
   - A DATA frame on a stream in `half-closed(remote)` or `closed` → `STREAM_CLOSED`.
   - Flow control accounting must happen **even for streams we have RST**, until the peer
     acknowledges — otherwise the connection window leaks and the connection eventually stalls.
     This is subtle, commonly missed, and produces a hang that looks like a network problem.
7. **`content-length` verification.** If the request declared `content-length`, the sum of DATA
   payload lengths must match it exactly at END_STREAM; mismatch → stream error
   `PROTOCOL_ERROR` (RFC 9113 §8.1.1).
8. **Empty DATA frame flood.** A peer can send unlimited zero-length DATA frames, which consume
   no flow control window but cost CPU. Bound with
   `Http2Limits.MAX_EMPTY_DATA_FRAMES_PER_STREAM`.

### Zero-alloc contract
- Small-body path (dispatch-on-END_STREAM): one copy into the user's `byte[]` when
  `bytes()` is called, and nothing else.
- Streaming path: 0 B/op at steady state; all buffers come from `DataBufferPool`.

### Safety checks
- [ ] Connection-level **and** stream-level WINDOW_UPDATE both sent
- [ ] Window overflow (> 2^31-1) rejected
- [ ] Window underflow (peer exceeds its window) rejected with the correct scope
- [ ] Padding counted toward flow control
- [ ] Flow control accounted for RST streams until settled
- [ ] `content-length` verified against actual DATA
- [ ] Empty DATA frame flood bounded
- [ ] `DataBufferPool` bounded; exhaustion applies backpressure rather than allocating without
      limit
- [ ] Body size bounded by `Http2Limits.MAX_REQUEST_BODY_SIZE` when no handler consumes it

### Tests
- `Http2FlowControlTest` — the classic scenarios: a 10 MB upload with a 64 KB window; a
  WINDOW_UPDATE arriving mid-write; a window shrink via SETTINGS producing a negative window;
  zero-increment; overflow.
- `Http2RequestBodyTest` — small inline path, streaming path, `content-length` mismatch,
  chunked-equivalent unknown length.
- `Http2LargeResponseTest` — a 100 MB streaming response completes without unbounded memory
  (assert peak heap).
- `Http2BackpressureTest` — a slow handler causes WINDOW_UPDATE to be withheld and the client to
  stall, rather than the server buffering without limit.
- `h2spec` sections 6.1 (DATA) and 6.9 (WINDOW_UPDATE) fully green.

### Docs
`flash/docs/http2/FLOW-CONTROL.md` — the two levels, the hysteresis policy, the backpressure story,
and the dispatch-on-END_STREAM optimization with its rationale.

### DoD
- [ ] 100 MB upload and 100 MB download both correct, both bounded memory.
- [ ] `h2spec` DATA and WINDOW_UPDATE sections green.
- [ ] Small-body path allocates exactly one `byte[]` (the user's body).

---

## Phase 12 — Trailers, half-close, gRPC

**Goal.** gRPC works, including streaming.

**Why now.** Trailers and half-close are the last protocol features gRPC needs, and they are the
ones most often forgotten — with the failure mode that every call fails with an unreadable
error.

### Tasks

1. **Receiving trailers.** A HEADERS frame arriving on a stream in `open` after DATA has been
   received is a trailer section. Rules:
   - It MUST carry `END_STREAM` (RFC 9113 §8.1); without it → `PROTOCOL_ERROR`.
   - It MUST NOT contain pseudo-headers → `PROTOCOL_ERROR`.
   - It is decoded through the same HPACK decoder and the same connection dynamic table —
     trailers are not a separate compression context.
   - Expose via a new `Request.trailers()` returning a `HeaderView`, available only after the
     body has been fully read. Document the ordering requirement. On h1, `Request.trailers()`
     returns the chunked trailer section (which `ChunkedInputStream.consumeTrailers` currently
     **discards** — fix that too, so the API is honest on both protocols; register as a new
     `EX-nn`).
2. **Sending trailers.** `Response.trailer(String, String)` and
   `Response.trailer(PreEncodedHeader)`. Emitted as a HEADERS frame with `END_STREAM` after the
   final DATA frame (which then must **not** carry `END_STREAM`). On h1 these become a chunked
   trailer section, and the response is forced to chunked encoding. One user-facing API, two
   correct renderings.
3. **Half-close.** Already modelled by the Phase 10 state machine; this phase exercises it.
   A handler must be able to finish reading the request body (peer sent END_STREAM →
   `half-closed(remote)`) and keep writing for a long time, and vice versa. Bidirectional
   streaming means both sides stay `open` while exchanging DATA.
4. **A streaming response API.** Today `Response` supports `stream(InputStream, long)` and
   `chunked(InputStream)` — both **pull** models where Flash reads from the user. gRPC server
   streaming needs a **push** model where the handler writes messages when it has them. Add:
   ```java
   public interface ResponseStream extends AutoCloseable {
       void write(byte[] data, int off, int len) throws IOException;  // one or more DATA frames
       void flush() throws IOException;
       void trailer(String name, String value);
       @Override void close() throws IOException;                     // END_STREAM (+ trailers)
   }
   Response.streaming(Consumer<ResponseStream> producer);
   ```
   This must work on h1 (chunked) and h2 (DATA frames) identically. `write` blocks the handler's
   virtual thread when the flow control window is exhausted — correct backpressure, no
   callbacks, no reactive types. This is where the virtual-thread bet pays off most visibly and
   it should be called out in the docs.
5. **CONNECT method** (RFC 9113 §8.5). Required for proxy use (Pathway). `:method CONNECT` with
   `:authority` and no `:scheme`/`:path`. The stream becomes a tunnel: DATA frames in both
   directions until END_STREAM. Implement the server side; the client side lands in Phase 14.
6. **gRPC end-to-end validation.** Stand up a real gRPC client (the `grpc-java` test client, or
   `grpcurl`) against a hand-written Flash handler that speaks the gRPC wire format for one
   unary method and one server-streaming method. Assert:
   - `content-type: application/grpc` round-trips,
   - the 5-byte length-prefixed message framing works,
   - `grpc-status: 0` arrives **in trailers**,
   - a non-zero `grpc-status` with `grpc-message` is readable by the client,
   - server streaming delivers N messages,
   - `te: trailers` on the request is accepted (and any other `te` value is rejected).
   This is a test, not a feature: Flash is not shipping a gRPC codec. Record that scope
   boundary in `DECISIONS.md` as `DEC-08`.

### Safety checks
- [ ] Trailers without `END_STREAM` rejected
- [ ] Pseudo-headers in trailers rejected
- [ ] Trailer count and size bounded (they go through the same HPACK limits)
- [ ] `ResponseStream.write` after `close` throws, does not corrupt the stream
- [ ] CONNECT tunnels are bounded by the same timeouts and flow control as normal streams

### Tests
- `Http2TrailersTest`, `Http1TrailersTest` (the h1 rendering), `TrailerParityTest`.
- `Http2HalfCloseTest` — all four half-close orderings.
- `ResponseStreamTest` — h1 and h2, including backpressure.
- `GrpcInteropTest` — the end-to-end validation above. Tagged so it can be excluded from the
  fast CI run if the gRPC dependency is heavy; it must still run on every PR to this branch.

### Docs
- `flash/docs/http2/TRAILERS-AND-STREAMING.md`.
- `README.md` — the `ResponseStream` API, with a gRPC-shaped example.

### DoD
- [ ] `grpcurl` completes a unary and a server-streaming call against a Flash handler.
- [ ] Trailers work on both protocols through one API.
- [ ] `FlashConfiguration.http2Enabled` flips to default `true` (the feature is now complete
      enough to be on by default) — or, if the team prefers a conservative rollout, stays
      `false` with the decision recorded.

---

## Phase 13 — Security hardening and abuse resistance

**Goal.** Make an HTTP/2 Flash server survive a hostile peer.

**Why separate.** The individual limits were introduced alongside their features, but the
*rate-based* and *composite* defences need the whole protocol present to be built and tested.
This phase is where an adversarial mindset is applied to the finished thing.

### Tasks

1. **Rapid Reset (CVE-2023-44487).** Opening a stream and immediately sending RST_STREAM does
   not count against `MAX_CONCURRENT_STREAMS`, so the limit is trivially bypassed and the server
   does unbounded work. Defence:
   - Track RST_STREAM received per rolling interval (`MAX_RESET_STREAMS_PER_INTERVAL` /
     `RESET_RATE_INTERVAL_MS`).
   - Track stream creations per interval (`MAX_STREAMS_CREATED_PER_INTERVAL`).
   - On breach: GOAWAY `ENHANCE_YOUR_CALM` and close.
   - Implement the counters with a simple two-bucket rolling window using `System.nanoTime()`,
     zero allocation, no timer thread.
2. **CONTINUATION flood (CVE-2024-27316).** Already bounded in Phase 7 by
   `MAX_CONTINUATION_FRAMES_PER_BLOCK` and `MAX_HEADER_LIST_SIZE`. Verify with an explicit
   attack test that sends 100 000 CONTINUATION frames and asserts the connection dies quickly
   and cheaply.
3. **HPACK bomb.** A small compressed block that decodes to an enormous header list. Bounded by
   `MAX_HEADER_LIST_SIZE`. Verify with a test that the bound is applied **during** decode, not
   after — a bomb must never be fully materialized.
4. **Settings flood.** A peer sending SETTINGS repeatedly forces an ACK each time. Bound the ACK
   rate; on breach, GOAWAY `ENHANCE_YOUR_CALM`.
5. **PING flood.** Same shape. Bound queued PING responses and the PING rate.
6. **Window-update flood, empty-DATA flood, priority flood** (PRIORITY frames are ignored but
   still cost parsing). Bound the aggregate rate of *any* frame that produces no application
   progress — a single `uselessFrameCounter` with one rolling window is simpler and more robust
   than six separate counters. Consider that design; record the choice.
7. **Slow-read attack.** A peer that opens many streams and reads responses slowly forces the
   server to buffer. Defence: the flow control design already bounds this (we never buffer more
   than the peer's window), plus `WRITE_TIMEOUT_MS` from Phase 3, plus a bound on total
   connection write-queue depth.
8. **Zero-length header names, duplicate pseudo-headers, oversized single header** — all
   already rejected; write explicit attack tests.
9. **Connection-level resource accounting.** Add an optional per-connection budget:
   total streams served, total bytes read, total connection lifetime
   (`Http2Limits.MAX_CONNECTION_LIFETIME_MS`, default off). Long-lived h2 connections are the
   norm, so these default to generous or disabled, but they must exist for operators behind a
   hostile edge.
10. **Review the whole `Http2Limits` surface** and expose the operationally-relevant ones on
    `FlashConfiguration` with sane defaults. A limit nobody can tune is a limit that gets
    forked.
11. **Re-run the h1 security tests** from Phase 1 against the h2 path where the concept
    translates (header count, header size, body size, timeouts) — several are protocol-neutral
    and must not have been lost in translation.

### Tests
`Http2AbuseTest` — one test per attack above, each asserting: the connection is terminated, the
correct error code is sent, the termination happens within a bounded time and a bounded amount
of allocated memory (assert with a heap sample, not a hope).

### Docs
`flash/docs/http2/SECURITY.md` — every limit, its default, the attack it prevents, the CVE where
applicable, and how to tune it. This is the document an operator reads at 3 a.m.

### DoD
- [ ] Every attack in this phase has a test that proves the defence.
- [ ] Every limit is documented with its rationale.
- [ ] A `security-review` pass over the whole `h2` package is completed and its findings fixed.

---

## Phase 14 — h2c prior knowledge and upstream/proxy support

**Goal.** Speak h2 without TLS (for internal service-to-service and for gRPC upstreams), and
speak h2 as a **client** so Pathway can proxy.

### Tasks

1. **h2c prior knowledge (server).** The detection already lives in `ProtocolNegotiator`
   (Phase 1 task 12). Wire it to `Http2Connection`. Gate on
   `FlashConfiguration.http2CleartextEnabled` (default `false`, because accepting h2c on a
   public port without TLS should be a deliberate choice).
2. **Do not implement `Upgrade: h2c`.** RFC 9113 §3.1 removed the HTTP/1.1 Upgrade mechanism
   (it was RFC 7540 §3.2 and is deprecated). Prior knowledge is what gRPC and every modern
   client use. Record as `DEC-10` with the citation, so nobody adds it later thinking it was an
   oversight.
3. **h2 client.** A minimal client-side implementation reusing every component:
   the same frame reader/writer, the same HPACK codec (the encoder now needs `:method`,
   `:scheme`, `:authority`, `:path` — all static-table entries), the same stream machine with
   the roles inverted. New: connection pooling, `:status` handling, and response assembly.
   Keep it in `dev.relism.flash.http2.client` and keep it honest about scope: it exists to serve
   the proxy use case, not to be a general-purpose HTTP client.
4. **Trailer relay.** A proxy must forward trailers in both directions, and must forward them
   *as trailers*, not fold them into headers. Getting this wrong is the single most common
   reason a gRPC proxy silently breaks. Explicit tests both ways.
5. **Hop-by-hop header handling.** A proxy must strip `connection`-listed headers and the
   standard hop-by-hop set when converting h1↔h2, and must not forward h2-forbidden headers.
   One shared table, one implementation, tested in all four conversion directions
   (h1→h1, h1→h2, h2→h1, h2→h2).
6. **`421 Misdirected Request`.** When connection coalescing sends us a request whose
   `:authority` we do not serve, the correct response is 421, which tells the client to open a
   new connection. Requires the status added in Phase 1 task 6. Only relevant when Flash serves
   multiple hostnames on one certificate (which `SniKeyManager` makes easy), so it is a real
   case here.

### Tests
- `H2cPriorKnowledgeTest`.
- `Http2ClientTest` — against Flash's own server, and against a third-party h2 server if one is
  available in CI.
- `ProxyTrailerRelayTest` — all four directions.
- `HopByHopHeaderTest` — all four directions.

### Docs
`flash/docs/http2/CLEARTEXT-AND-PROXY.md`.

### DoD
- [ ] gRPC over h2c works end to end.
- [ ] Trailers survive a Flash→Flash proxy hop in both directions.

---

## Phase 15 — RFC 8441 extended CONNECT (WebSocket over HTTP/2)

**Goal.** Close the functional gap that HTTP/2 opens: today's WebSocket upgrade path is
HTTP/1.1-only, so an h2 client cannot open a WebSocket against Flash.

**Why it matters.** `HttpServer.process:307` (now `Http1Connection`) detects the upgrade via
`Connection: Upgrade` + `Upgrade: websocket` — headers that are **forbidden** in HTTP/2. A
browser that negotiates h2 for a page and then opens a WebSocket currently falls back to a
separate h1 connection, which works but is a wart; and an h2-only client simply cannot. RFC 8441
defines the h2 mechanism.

### Tasks

1. Advertise `SETTINGS_ENABLE_CONNECT_PROTOCOL` (id `0x8`, value 1). Note this is a **seventh**
   settings parameter beyond RFC 9113's six — `Http2Settings` (Phase 8) must already tolerate
   unknown ids, so this is additive.
2. Accept `:method CONNECT` with `:protocol websocket`, `:scheme`, `:path`, `:authority`.
   The `:protocol` pseudo-header is new and must be added to `PseudoHeaders` validation
   (it is only legal when `SETTINGS_ENABLE_CONNECT_PROTOCOL` was sent and the method is CONNECT).
3. Route it through the **existing** `AbstractWsRouter` — the same `ws(path, handler)`
   registrations serve both protocols. Verify that `FastPathWsRouterImpl` needs no changes.
4. There is no `Sec-WebSocket-Key`/`Sec-WebSocket-Accept` handshake on h2 (the stream itself is
   the handshake); respond `:status 200` and the stream becomes the WebSocket data channel.
   The `WS_HANDSHAKE_PREFIX`/`WS_GUID_BYTES` machinery is h1-only — confirm it is not reachable
   from the h2 path.
5. `WebSocketSession` must accept an h2 stream as its transport instead of a raw socket. This
   requires abstracting its `InputStream`/`OutputStream` pair behind a small interface — which
   the Phase 2 `WebSocketFrameCodec` extraction should already have made possible. If it did
   not, that is a Phase 2 design miss to correct here and to note in the registry.
6. WebSocket frames are carried in DATA frames and are therefore **flow-controlled**. A
   WebSocket message larger than the window is split across DATA frames; the framing layers must
   not be confused with each other. Test with messages spanning many DATA frames.
7. Masking: RFC 6455 masking still applies to client→server frames over h2 (RFC 8441 does not
   remove it). The existing `unmaskInPlace` is reused unchanged.

### Tests
- `WebSocketOverH2Test` — open, echo, fragmented message, large message spanning DATA frames,
  close.
- `WebSocketParityTest` — the same `WebSocketHandler` behaves identically on h1 and h2.

### Docs
- `README.md` — WSS/WS over h2 is transparent, same `ws(path, handler)` API.
- `flash/docs/http2/WEBSOCKET.md`.

### DoD
- [ ] A browser negotiating h2 can open a WebSocket to a Flash `ws()` route.
- [ ] `AbstractWsRouter` and `FastPathWsRouterImpl` unchanged.

---

## Phase 16 — Compliance test suite

**Goal.** A repeatable, CI-integrated proof of 100 % conformance.

### Tasks

1. **`h2spec` integration.** `h2spec` is the reference conformance suite for RFC 9113 and
   RFC 7541. Wire it into CI: start a Flash server on a random port in a `@BeforeAll`, run the
   `h2spec` binary against it, parse the output, fail the build on any failure.
   - Run both the TLS (`h2`) and cleartext (`h2c`) modes.
   - Pin the `h2spec` version; record it.
   - **Zero failures. Zero skips.** If a case is genuinely inapplicable, that must be argued in
     `flash/docs/http2/COMPLIANCE.md` with the RFC citation, not silently excluded.
2. **RFC 7541 Appendix C vectors** as a standalone parameterized test (already required by
   Phase 7, restated here as part of the permanent suite).
3. **Fuzzing.** Property/fuzz tests for: the frame reader, the HPACK decoder, the Huffman
   decoder, the pseudo-header validator, and the h1 request parser. Requirements for all:
   only typed protocol exceptions may escape; no `OutOfMemoryError`; no infinite loop (per-case
   timeout); no unbounded allocation (heap assertion). Use jqwik or a hand-rolled deterministic
   random with a recorded seed so failures reproduce.
4. **Interoperability matrix.** Automated where possible, documented where not:

   | Client | Mode | Must pass |
   |---|---|---|
   | `curl --http2` | TLS | GET, POST, large upload, large download |
   | `curl --http2-prior-knowledge` | cleartext | same |
   | Java `HttpClient` `Version.HTTP_2` | TLS | same, plus concurrent streams |
   | `nghttp` | TLS + cleartext | verbose frame trace inspected for correctness |
   | `grpcurl` / `grpc-java` | cleartext | unary, server streaming, client streaming, bidi |
   | Chrome/Firefox | TLS | manual smoke test per release, documented checklist |

5. **Concurrency and soak tests.**
   - `Http2ConcurrencyTest` — 1000 concurrent streams on one connection, all correct.
   - A soak test: 10 minutes of sustained mixed traffic (GET, POST, streaming, RST, PING) with
     heap and pool-size assertions at the end. Tagged for nightly, not per-PR.
6. **Regression corpus.** Every bug found during implementation gets a test with the exact
   frame bytes that triggered it, checked in under `src/test/resources/http2/regressions/`.

### Docs
`flash/docs/http2/COMPLIANCE.md` — the `h2spec` result table, the interop matrix with versions, the
list of deliberately-unimplemented features with RFC citations (server push, priority
scheduling, `Upgrade: h2c`), and the fuzzing methodology.

### DoD
- [ ] `h2spec` 100 % pass, both modes, zero skips, in CI.
- [ ] Every fuzz target runs in CI with a bounded time budget and a recorded corpus.
- [ ] The interop matrix is filled in with actual versions and dates.

---

## Phase 17 — Benchmarks, allocation gates, tuning

**Goal.** Prove "throughput and latency unmatched" with numbers, and prevent regression.

### Tasks

1. **JMH benchmark suite** covering:
   - h1 GET (baseline, captured before Phase 1 and re-measured after every phase)
   - h2 GET, 1 stream per connection
   - h2 GET, 8 / 64 / 256 concurrent streams per connection
   - h2 POST with a 1 KB body (unary-gRPC shape)
   - h2 streaming response, 1 MB
   - HPACK decode of a typical browser header block
   - HPACK encode of a typical response header block
   - Frame reader throughput
   - The Phase 3 writer, at every contention level
2. **Allocation gates.** `-prof gc`, asserting `gc.alloc.rate.norm == 0` for:
   h1 GET happy path, h2 GET happy path, h2 response write, HPACK decode, HPACK encode, frame
   read. **A non-zero value fails CI.** This is the mechanism that keeps `R2` true after this
   plan's authors have moved on.
3. **Latency gates.** p50/p99/p999 recorded per benchmark, with a regression threshold
   (e.g. fail if p99 regresses more than 10 % versus the recorded baseline). Baselines are
   checked into `flash/docs/http2/BASELINES.md` and updated deliberately, with justification, never
   silently.
4. **End-to-end load testing** with `h2load` (ships with nghttp2):
   - requests/sec at 1, 10, 100, 1000 concurrent connections × 1, 10, 100 streams
   - compare against the h1 numbers on the same hardware
   - compare against at least one reference implementation (Netty-based, or `nghttpd`) so the
     "unmatched" claim is measured against something rather than asserted
5. **Tuning pass**, guided by the numbers, not by intuition. Candidate knobs, each to be
   measured and then either adopted with its number recorded or rejected with its number
   recorded:
   - `SETTINGS_MAX_FRAME_SIZE` we advertise (16 KB vs 64 KB vs 1 MB)
   - `SETTINGS_INITIAL_WINDOW_SIZE` we advertise
   - WINDOW_UPDATE hysteresis threshold
   - `INLINE_BODY_THRESHOLD`
   - `ScratchPool` bound and `DataBufferPool` chunk size
   - the `EX-04` word-at-a-time router path (adopt or revert)
   - the `EX-33` SWAR header scan (adopt or revert)
   - `SlicePool` size
   - whether Huffman-encoding runtime values is a win (`DEC-05`'s flag)
6. **Profiling pass** with async-profiler: allocation profile (must be empty on the gated
   paths), CPU profile (identify the top 10 methods and justify each), and lock profile
   (the writer lock must not appear in the top contended locks at realistic concurrency).
7. **Carrier-pinning check.** `-Djdk.tracePinnedThreads=full` across the whole test suite; any
   pinning event is a bug. Add it to CI.
8. **Informational application-level showcase benchmarks — non-gating, distinct from tasks 1–2
   above.** Recorded as a goal during Phase 3's wrap-up (`DECISIONS.md`, `DEC-18`); not
   implemented yet. Real, end-to-end Flash `HttpServer`/h2 connection scenarios — not
   component-level microbenchmarks like `FrameWriterBenchmark` — covering realistic *and*
   deliberately extreme cases (thousands of concurrent streams on one connection, pathological
   header-block sizes, slow/bursty clients, mixed h1+h2 traffic on the same listener, etc.).
   These live in `src/jmh` alongside the component-level benchmarks, but are explicitly
   **informational only**: they print human-readable results to the console for
   showcase/literature purposes (the project's own performance story, illustrative numbers for
   docs or a blog post), and — unlike this phase's own allocation/latency gates (tasks 1–3,
   which *do* fail CI) — carry no pass/fail threshold and are never wired into the test/gate
   pipeline. See `DEC-18` for the full rationale.

### Docs
`flash/docs/http2/PERFORMANCE.md` — methodology, hardware, numbers, the comparison, the tuning
decisions and the rejected ones. Every claim in the project's marketing about performance must
be traceable to a number in this file.

### DoD
- [ ] Allocation gates green in CI and wired to fail the build.
- [ ] Latency baselines recorded.
- [ ] h1 performance is not worse than the pre-Phase-1 baseline.
- [ ] No carrier pinning anywhere.
- [ ] `flash/docs/http2/PERFORMANCE.md` complete with the comparison against a reference server.

---

## Phase 18 — Documentation

**Goal.** The feature is not done until someone else can use it, operate it, and extend it.

### Deliverables

**User-facing (`README.md`):**
- HTTP/2 in the feature list and the architecture diagram.
- `FlashConfiguration`: `http2Enabled`, `http2CleartextEnabled`, all the timeouts from Phase 1,
  `sendDate`, and the h2 tunables promoted in Phase 13 task 10 — added to the existing config
  table (lines 161-170).
- A "Protocols" section: what is negotiated, how, and what the user must do (nothing, in the
  common case).
- The **object lifetime** section from Phase 6 — this is a new user-visible contract and
  burying it would be irresponsible.
- The `ResponseStream` API from Phase 12.
- `PreEncodedHeader` from Phase 9.
- WebSocket over h2 from Phase 15.
- An explicit statement of what Flash does **not** implement and why (server push, priority
  scheduling, `Upgrade: h2c`), so users do not go looking.

**Operator-facing (`flash/docs/http2/`):**
- `SECURITY.md` (Phase 13) — every limit, every default, every attack, how to tune.
- `PERFORMANCE.md` (Phase 17).
- `COMPLIANCE.md` (Phase 16).
- `TROUBLESHOOTING.md` — new: how to read a `GOAWAY` in the logs, what each error code means in
  practice, how to enable frame tracing, the three most likely misconfigurations.

**Contributor-facing (`flash/docs/http2/`):**
- `TRANSPORT.md` (Phase 2), `BYTES.md` (Phase 4), `WRITER.md` (Phase 3), `FRAMES.md` (Phase 5),
  `MESSAGE-MODEL.md` (Phase 6), `HPACK.md` (Phases 7, 9), `CONNECTION.md` (Phase 8),
  `STREAMS.md` (Phase 10), `FLOW-CONTROL.md` (Phase 11),
  `TRAILERS-AND-STREAMING.md` (Phase 12), `CLEARTEXT-AND-PROXY.md` (Phase 14),
  `WEBSOCKET.md` (Phase 15), `HTTP1-HARDENING.md` (Phase 1).
- `DECISIONS.md` — complete, every `DEC-nn`.
- `flash/docs/http2/README.md` — an index page linking all of the above, with a one-paragraph
  orientation for someone opening the package for the first time.

**Javadoc:**
- Every public type in `dev.relism.flash.http2` and the new `transport`/`http1`/`bytes` packages.
- The release workflow publishes Javadoc to GitHub Pages (`release.yml`); verify the new
  packages render correctly and that no `@link` is broken.

**Maintenance:**
- Update `AGENTS.md` if the scope list changed.
- Update the root `README.md` module table if any module boundary moved.
- Re-read every Javadoc this plan touched and verify none of them still describe the old
  behaviour. `HttpServer`'s ThreadLocal Javadoc (`EX-06`) is the cautionary example: a comment
  that confidently states something false is worse than no comment.

### DoD
- [ ] Every document listed above exists and is accurate.
- [ ] `mvn javadoc:javadoc` produces no warnings.
- [ ] A reader who knows HTTP/1.1 and nothing about HTTP/2 can read `flash/docs/http2/README.md` and
      understand the architecture. (Verify by having someone who did not implement it read it.)

---

# PART IV — Testing strategy (cross-cutting)

## Test layers

| Layer | What it proves | Where |
|---|---|---|
| Unit | Each component in isolation, including every rejection path | `src/test/java/**` |
| RFC vectors | Byte-exact conformance for HPACK and Huffman | `HpackDecoderTest`, `HuffmanTest` |
| Property/fuzz | No crash, no hang, no unbounded allocation on hostile input | `*FuzzTest` |
| State machine | Every cell of every transition table | `Http2StreamStateTest` |
| Integration | Real client, real socket, real TLS | `HttpServerTest`-style |
| Conformance | `h2spec`, 100 %, both modes | `H2SpecComplianceTest` |
| Interop | curl, nghttp, Java HttpClient, grpcurl, browsers | Phase 16 matrix |
| Concurrency | 1000 streams, stress, leak, pinning | `*ConcurrencyTest`, `*LeakTest` |
| Allocation | 0 B/op gates | JMH `-prof gc` in CI |
| Performance | Throughput and latency baselines | JMH + `h2load` |
| Regression | Every bug ever found, by its exact bytes | `src/test/resources/http2/regressions/` |

## Rules

1. **Every rejection has a test asserting the specific error code**, not merely that something
   was thrown. `PROTOCOL_ERROR` where the RFC says `FRAME_SIZE_ERROR` is a conformance failure
   that `h2spec` will catch — catch it first.
2. **Every fuzz target has a per-case timeout.** An infinite loop on hostile input is a DoS, and
   a fuzz test without a timeout will hang CI instead of reporting it.
3. **Every pool has a leak test.** Open and close 100 000 of whatever it pools; assert the pool
   size is stable and the heap is flat.
4. **Every "0 B/op" claim has a JMH assertion.** Claims without gates decay.
5. **The h1 test suite is the regression oracle for Phases 1–6.** It must pass with only import
   changes. Any semantic change to an existing test is called out in the PR with justification.
6. **Tests for concurrency bugs must be written to fail first** against the naive implementation
   (`HpackEvictionRaceTest` is the template). A green test that would also be green against the
   bug proves nothing.
7. **Run the suite under `-Djdk.virtualThreadScheduler.parallelism=1`** in at least one CI job.
   Many virtual-thread bugs (pinning, lost wakeups, assumed parallelism) only appear there.

---

# PART V — Documentation deliverables (index)

| Document | Phase | Audience |
|---|---|---|
| `flash/docs/http2/README.md` | 18 | everyone — the index and orientation |
| `flash/docs/http2/IMPLEMENTATION-PLAN.md` | — | this file |
| `flash/docs/http2/DECISIONS.md` | 0, ongoing | contributors |
| `flash/docs/http2/HTTP1-HARDENING.md` | 1 | operators |
| `flash/docs/http2/TRANSPORT.md` | 2 | contributors |
| `flash/docs/http2/WRITER.md` | 3 | contributors |
| `flash/docs/http2/BYTES.md` | 4 | contributors |
| `flash/docs/http2/FRAMES.md` | 5 | contributors |
| `flash/docs/http2/MESSAGE-MODEL.md` | 6 | contributors + users (lifetime contract) |
| `flash/docs/http2/HPACK.md` | 7, 9 | contributors |
| `flash/docs/http2/CONNECTION.md` | 8 | contributors |
| `flash/docs/http2/STREAMS.md` | 10 | contributors |
| `flash/docs/http2/FLOW-CONTROL.md` | 11 | contributors + operators |
| `flash/docs/http2/TRAILERS-AND-STREAMING.md` | 12 | users |
| `flash/docs/http2/SECURITY.md` | 13 | operators |
| `flash/docs/http2/CLEARTEXT-AND-PROXY.md` | 14 | users |
| `flash/docs/http2/WEBSOCKET.md` | 15 | users |
| `flash/docs/http2/COMPLIANCE.md` | 16 | everyone |
| `flash/docs/http2/PERFORMANCE.md` | 17 | everyone |
| `flash/docs/http2/BASELINES.md` | 17 | CI + contributors |
| `flash/docs/http2/TROUBLESHOOTING.md` | 18 | operators |
| `README.md` (updated) | 1, 2, 6, 9, 12, 15, 18 | users |
| `AGENTS.md` (updated) | 0 | contributors |

---

# PART VI — Appendices

## Appendix A — Decision log seed

These go into `flash/docs/http2/DECISIONS.md` at Phase 0. Each subsequent non-obvious choice appends
an entry in the same format: **Context / Options / Decision / Consequence / Revisit when**.

| Id | Decision | One-line rationale |
|---|---|---|
| `DEC-01` | HTTP/2 lives in `flash` core, package `dev.relism.flash.http2`, not an extension | The protocol branch must sit where the transport sits; `HttpServer` is package-private |
| `DEC-02` | h1 and h2 are peers behind a `ConnectionProtocol` seam, never flags in shared code | `R1`; protects h1 performance and both implementations' readability |
| `DEC-03` | `ReentrantLock` everywhere, never `synchronized` around blocking I/O | Java 21 pins carriers on `synchronized`; JEP 491 is JDK 24+ |
| `DEC-04` | The HPACK **encoder** uses the static table only; no dynamic table | Removes all shared mutable state from the write path, at a cost of a few bytes on the wire |
| `DEC-05` | Huffman-encode constants at boot; emit runtime values as raw literals | Keeps the encode loop off the hot path; flag provided so it can be measured |
| `DEC-06` | Decoded headers are copied into a **per-stream** arena, not referenced in the dynamic table | Eliminates the eviction/multiplexing data race by construction; refcounting rejected |
| `DEC-07` | `:authority` is exposed to user code as both `:authority` and `host` | Existing middleware reads `Host`; breaking that silently would be worse than the small duplication |
| `DEC-08` | Flash ships HTTP/2, not a gRPC codec | gRPC interop is a **test**, proving the protocol features gRPC needs are present and correct |
| `DEC-09` | *(Phase 3)* The chosen writer design, with its benchmark numbers | To be written when the gate is evaluated |
| `DEC-10` | `Upgrade: h2c` is deliberately **not** implemented | RFC 9113 §3.1 removed it; prior knowledge is what modern clients use |

## Appendix B — HTTP/2 frame types

| Type | Id | Stream id | Length constraint | Flags | Flow-controlled | Flash |
|---|---|---|---|---|---|---|
| DATA | 0x0 | non-zero | ≤ MAX_FRAME_SIZE | END_STREAM, PADDED | yes | full |
| HEADERS | 0x1 | non-zero | ≤ MAX_FRAME_SIZE | END_STREAM, END_HEADERS, PADDED, PRIORITY | no | full |
| PRIORITY | 0x2 | non-zero | exactly 5 | — | no | parse + ignore (RFC 9113 §5.3.2) |
| RST_STREAM | 0x3 | non-zero | exactly 4 | — | no | full |
| SETTINGS | 0x4 | zero | multiple of 6 | ACK | no | full |
| PUSH_PROMISE | 0x5 | non-zero | ≤ MAX_FRAME_SIZE | END_HEADERS, PADDED | no | reject on receive; never sent |
| PING | 0x6 | zero | exactly 8 | ACK | no | full |
| GOAWAY | 0x7 | zero | ≥ 8 | — | no | full, two-stage |
| WINDOW_UPDATE | 0x8 | zero or non-zero | exactly 4 | — | no | full |
| CONTINUATION | 0x9 | non-zero | ≤ MAX_FRAME_SIZE | END_HEADERS | no | full, bounded |
| *(unknown)* | > 0x9 | any | any | any | no | ignore, except inside a header block |

## Appendix C — HTTP/2 error codes (RFC 9113 §7)

| Code | Name | Typical use in Flash |
|---|---|---|
| 0x00 | `NO_ERROR` | graceful GOAWAY |
| 0x01 | `PROTOCOL_ERROR` | malformed request, bad stream id, forbidden header |
| 0x02 | `INTERNAL_ERROR` | unexpected exception, write timeout |
| 0x03 | `FLOW_CONTROL_ERROR` | window overflow/underflow |
| 0x04 | `SETTINGS_TIMEOUT` | peer never ACKed our SETTINGS |
| 0x05 | `STREAM_CLOSED` | frame on a closed stream |
| 0x06 | `FRAME_SIZE_ERROR` | wrong frame length for its type |
| 0x07 | `REFUSED_STREAM` | `MAX_CONCURRENT_STREAMS` exceeded (client may retry) |
| 0x08 | `CANCEL` | received from client on cancellation |
| 0x09 | `COMPRESSION_ERROR` | any HPACK failure |
| 0x0a | `CONNECT_ERROR` | CONNECT tunnel failure |
| 0x0b | `ENHANCE_YOUR_CALM` | rate limits: rapid reset, PING flood, SETTINGS flood |
| 0x0c | `INADEQUATE_SECURITY` | TLS below the RFC 9113 §9.2 requirements |
| 0x0d | `HTTP_1_1_REQUIRED` | not used (we support h2 fully) |

## Appendix D — HPACK static table (RFC 7541 Appendix A)

Reproduce in full in `HpackStaticTable`. Entries 1–61:

```
 1 :authority                      32 content-type
 2 :method GET                     33 expires
 3 :method POST                    34 from
 4 :path /                         35 host
 5 :path /index.html               36 if-match
 6 :scheme http                    37 if-modified-since
 7 :scheme https                   38 if-none-match
 8 :status 200                     39 if-range
 9 :status 204                     40 if-unmodified-since
10 :status 206                     41 last-modified
11 :status 304                     42 link
12 :status 400                     43 location
13 :status 404                     44 max-forwards
14 :status 500                     45 proxy-authenticate
15 accept-charset                  46 proxy-authorization
16 accept-encoding gzip, deflate   47 range
17 accept-language                 48 referer
18 accept-ranges                   49 refresh
19 accept                          50 retry-after
20 access-control-allow-origin     51 server
21 age                             52 set-cookie
22 allow                           53 strict-transport-security
23 authorization                   54 transfer-encoding
24 cache-control                   55 user-agent
25 content-disposition             56 vary
26 content-encoding                57 via
27 content-language                58 www-authenticate
28 content-length                  59 (none — table ends at 61)
29 content-location                60
30 content-range                   61
31 content-type (name only, see 32 note)
```

**The implementer must transcribe the table from RFC 7541 Appendix A directly, not from this
summary.** The summary above is an orientation aid and its exact index assignments must be
verified against the RFC before use — a single off-by-one in the static table corrupts every
request on the connection. Add a test that asserts the table's SHA-256 against a value derived
from the RFC text, so a transcription error is caught once and never again.

## Appendix E — Per-phase completion checklist

| Phase | Ships | Gate |
|---|---|---|
| 0 | Package skeleton, limits, error model, decision log | compiles, no TODOs |
| 1 | h1 security fixes, ALPN/preface plumbing | security tests green, no h1 regression |
| 2 | Transport decomposed, scratch pooled, WS fixed | no `ThreadLocal`, no blocking `synchronized` |
| 3 | The serialized writer | **GO/NO-GO gate criteria met** |
| 4 | Byte layer, header index, view capabilities | h1 happy path 0 B/op |
| 5 | Frame reader/writer/validator | fuzz green, all 10 types |
| 6 | Pooled message model | h1 full cycle 0 B/op, API unchanged |
| 7 | HPACK decoder | every RFC 7541 Appendix C vector, eviction race test |
| 8 | Connection state machine | h2spec §3,4,6.5,6.7,6.8,6.9 |
| 9 | HPACK encoder, precompilation, response path | `:status 200` = one byte, parity test |
| 10 | Streams, dispatch, h2 requests | `curl --http2` serves a real route, h2spec §5,§8 |
| 11 | DATA, flow control, bodies | 100 MB up and down, h2spec §6.1,§6.9 |
| 12 | Trailers, half-close, streaming API | `grpcurl` unary + streaming |
| 13 | Abuse resistance | every attack has a passing defence test |
| 14 | h2c, client, proxy | gRPC over h2c, trailer relay both ways |
| 15 | WebSocket over h2 | browser WS over an h2 connection |
| 16 | Compliance suite | h2spec 100 %, zero skips, in CI |
| 17 | Benchmarks and gates | allocation gates in CI, baselines recorded |
| 18 | Documentation | every doc in Part V exists and is accurate |

## Appendix F — Standing instruction

Restating `R10`, because it is the instruction most likely to be forgotten under deadline
pressure and it is the one the project owner asked for most explicitly:

> While implementing any phase, if you find that existing code does something unnecessary,
> lacks a safety check, allocates avoidably, could be precompiled at boot, has a correctness or
> compliance bug, or is structured in a way that obstructs the work — **fix it in that phase**.
> Register it as a new `EX-nn` in Part II. Add a regression test. Mention it in the PR
> description. Do not open a TODO, do not defer it, and do not work around it.
>
> The registry in Part II came from reading the codebase once. It is a floor, not a ceiling.
