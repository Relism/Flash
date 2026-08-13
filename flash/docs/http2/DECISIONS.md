# Flash HTTP/2 — Decision Log

This is the living record of every non-obvious choice made while implementing
`flash/docs/http2/IMPLEMENTATION-PLAN.md`. It is not a changelog of what was built — the git
history is that — it is a record of *why*, for choices that were not forced by the RFC and that
a future reader would otherwise have to re-derive or, worse, silently re-litigate.

Every entry: **Context / Options / Decision / Consequence / Revisit when**.

Seeded at Phase 0 with `DEC-01`…`DEC-10` (the decisions already implied by the plan itself, per
Appendix A). Every subsequent non-obvious choice appends a new entry with the next free number.
Numbers are never reused, even if a decision is later reversed — the reversal gets its own entry
that supersedes the earlier one and says so explicitly.

---

## DEC-01 — HTTP/2 lives in `flash` core, package `dev.relism.flash.h2`, not an extension

**Context.** Flash has an extension mechanism (`flash-ext-*` modules) for optional
functionality. HTTP/2 could in principle be shipped as `flash-ext-h2`.

**Options.**
1. Ship as an extension, loaded optionally.
2. Ship in `flash` core, alongside HTTP/1.1.

**Decision.** Core (option 2).

**Consequence.** The protocol decision (h1 vs h2) is made once, immediately after
ALPN/preface detection, inside the transport layer. `HttpServer` (and its Phase 2 replacement)
is package-private to `flash` core; an extension cannot hook into ALPN negotiation or the
accept loop without core exposing seams it does not otherwise need. HTTP/2 is a transport
concern in the same sense HTTP/1.1 is — it cannot be optional in the way, say, an OpenAPI
generator is.

**Revisit when.** Never, absent a restructuring of the extension mechanism itself to support
transport-level extensions (not currently planned).

---

## DEC-02 — h1 and h2 are peers behind a `ConnectionProtocol` seam, never flags in shared code

**Context.** The obvious shortcut is `if (isHttp2) { ... } else { ... }` scattered through the
existing HTTP/1.1 code paths.

**Options.**
1. Flag-branch inside shared code.
2. A `ConnectionProtocol` interface with two implementations (`Http1Connection`,
   `Http2Connection`), selected once per connection.

**Decision.** Option 2 (R1).

**Consequence.** Shared code (byte scanning, the writer discipline, `Request`/`Response`) is
extracted upward into protocol-neutral components (`dev.relism.flash.bytes`,
`ResponseSerializer`), never pushed sideways with a protocol flag. This is enforced by an
architecture test (Phase 2) asserting `dev.relism.flash.http1` never references
`dev.relism.flash.h2` and vice versa. The cost is more up-front extraction work in Phase 2 and
Phase 6; the benefit is that h1 throughput cannot regress from an `if` that the JIT fails to
eliminate, and that either implementation can be read in isolation.

**Revisit when.** Never — this is a structural invariant, not a tunable.

---

## DEC-03 — `ReentrantLock` everywhere, never `synchronized` around blocking I/O

**Context.** Java 21 (this project's baseline) has virtual threads (JEP 444) but not JEP 491
(which removes `synchronized` carrier-pinning); JEP 491 lands in JDK 24. A virtual thread that
blocks inside a `synchronized` block pins its carrier platform thread for the duration of the
block, including any blocking I/O inside it.

**Options.**
1. Keep `synchronized` where it already exists (`WebSocketSession`, `EX-01`) and accept the
   pinning risk.
2. Replace every `synchronized` block that can block on I/O with `java.util.concurrent.locks
   .ReentrantLock`, which unmounts a blocked virtual thread instead of pinning its carrier.

**Decision.** Option 2, applied retroactively to the existing WebSocket code (Phase 2) and as a
standing rule for every future connection-writer path, most importantly `Http2FrameWriter`
(Phase 3).

**Consequence.** One virtual thread blocking on a slow write no longer starves the carrier pool
for every other connection scheduled onto that carrier. The cost is that `ReentrantLock` is
slightly more expensive than an uncontended `synchronized` monitor in the*platform-thread* case
— irrelevant here, since every request-serving thread in this codebase is virtual.

**Revisit when.** The project's Java baseline moves to JDK 24+ and JEP 491 is confirmed to
remove pinning for `synchronized`. Even then, `ReentrantLock`'s explicit `tryLock()` — which
`synchronized` cannot offer — is load-bearing for Phase 3's writer design, so this decision
would only partially reverse.

---

## DEC-04 — The HPACK **encoder** uses the static table only; no dynamic table

**Context.** RFC 7541's dynamic table is optional for an encoder (a decoder must always
support the peer using one; nothing requires the encoder to use one itself). Using it on the
encode side would save bytes on repeated headers (e.g. a constant `server` value) but requires
mutable, connection-shared state: an insertion changes indices for every subsequent encode on
that connection.

**Options.**
1. Encoder uses the dynamic table, saving bytes on repeated custom headers.
2. Encoder emits only Indexed (static) and Literal-Without-Indexing representations; no dynamic
   table, no mutable encoder state.

**Decision.** Option 2.

**Consequence.** The write path — already the project's largest architectural risk (Phase 3) —
needs no shared-table lock and no invalidation protocol across concurrently-writing streams.
The cost is a few extra bytes per response for headers that do not already have a static-table
entry (i.e. everything except the ~30 header names RFC 7541 Appendix A knows about). The
encoder still honours the peer's `SETTINGS_HEADER_TABLE_SIZE` by sending a Dynamic Table Size
Update of 0 at the start of the first header block, declaring "I will never use this table" —
a correctness detail, not optional politeness (Phase 9 task 1).

**Revisit when.** Benchmark evidence (Phase 17) shows the extra wire bytes materially hurt
throughput or latency on a realistic workload — not before. A shared dynamic table is a
non-trivial correctness surface (see `DEC-06`'s discussion of the analogous decode-side hazard)
and should only be taken on with a measured reason.

---

## DEC-05 — Huffman-encode constants at boot; emit runtime values as raw literals

**Context.** HPACK lets the encoder Huffman-code any string at its option. Constants (status
lines, `content-type` values) are a closed, known set and can be Huffman-encoded once, at class
initialization, for free at runtime. Runtime-generated values (a dynamic `ETag`, a user-set
custom header) would need to be Huffman-encoded on every response.

**Options.**
1. Huffman-encode everything, including runtime values, on every write.
2. Huffman-encode only boot-time constants; emit runtime values as raw (uncompressed) literals.

**Decision.** Option 2, with `FlashConfiguration.h2HuffmanDynamicValues` (default `false`) so
option 1's cost/benefit can actually be measured on real traffic rather than argued about in
the abstract.

**Consequence.** The response write path's critical section has no per-byte Huffman encode
loop for the common case. The cost is a few extra bytes on the wire for runtime header values,
which HPACK's other mechanisms (indexing on the receive side, if the receiver chooses to use
its dynamic table) can still partially recover.

**Revisit when.** Phase 17 benchmarks the flag both ways on a representative response shape.

---

## DEC-06 — Decoded headers are copied into a **per-stream** arena, not referenced in the dynamic table

**Context.** A `ByteView` into the HPACK dynamic table's arena is valid only while its entry is
still live. Under HTTP/1.1 this is trivially safe (one thread, one request at a time). Under
HTTP/2, the demux thread can decode a second stream's HEADERS — evicting and overwriting
dynamic-table arena bytes — while a handler on a different virtual thread is still reading a
view produced by an earlier decode. This is a genuine, silent data race: it does not manifest
in any test that decodes one block at a time, only under real multiplexed load.

**Options.**
1. Reference dynamic-table entries directly from decoded `ByteView`s, and protect them with an
   epoch or reference-count scheme so an entry cannot be evicted while still referenced.
2. Copy every decoded header (name and value) into an arena owned by the stream being
   assembled, at decode time. One `~30`-byte-average `memcpy` per header; correctness by
   construction, no cross-thread coordination.

**Decision.** Option 2.

**Consequence.** Header decode is not zero-copy relative to the dynamic table (R3's "honest
naming" clause applies: HTTP/2 copies each novel header once per connection and references it
by index thereafter — the per-stream arena copy is that one copy). In exchange, no handler can
ever observe a torn or evicted header value, and the demux thread never needs to coordinate
with a handler thread to decode the next block. Per-stream arenas are pooled (returned on
stream close) so this is zero allocation at steady state despite the copy.

**Revisit when.** Profiling (Phase 17) shows the per-header copy is a measurable cost on a
realistic HPACK-heavy workload. Even then, option 1's concurrent bookkeeping is a large
correctness surface to take on to avoid a small `memcpy`, and should not be revisited casually.

---

## DEC-07 — `:authority` is exposed to user code as both `:authority` and `host`

**Context.** HTTP/2 requests carry authority information in the `:authority` pseudo-header
(RFC 9113 §8.3.1), not a `Host` header — `host` may optionally also be present and, if so, must
match `:authority`, but is not required. Existing Flash middleware (and most middleware in the
wild) reads `Host` by convention, inherited from HTTP/1.1.

**Options.**
1. Expose only `:authority`, under whatever name the h2 header map uses for pseudo-headers.
   Middleware written against `Host` silently breaks on h2.
2. Expose `:authority`'s value under both keys: the literal `:authority` and `host`.

**Decision.** Option 2.

**Consequence.** A single small duplication (one extra index entry into the same per-stream
arena bytes — no extra copy) buys behavioural parity for existing and future middleware that
reads `Host`, without requiring every middleware author to special-case h2. Documented in
`flash/docs/http2/STREAMS.md`.

**Revisit when.** Not planned to be revisited; this is a compatibility shim with negligible
cost, not a design compromise under pressure.

---

## DEC-08 — Flash ships HTTP/2, not a gRPC codec

**Context.** gRPC is one of the strongest motivations for HTTP/2 support (Pathway's upstream
use case), and it is tempting to let that motivation expand scope into shipping gRPC framing,
proto codecs, or a service-definition layer.

**Options.**
1. Ship a gRPC codec/framework alongside HTTP/2 transport support.
2. Ship HTTP/2 transport only; validate gRPC compatibility with an interop test, not a feature.

**Decision.** Option 2.

**Consequence.** Phase 12's `GrpcInteropTest` proves that the protocol features gRPC actually
needs — trailers, `content-type: application/grpc`, `te: trailers`, half-close, streaming — are
present and correct, using a real gRPC client against a hand-written Flash handler that speaks
the wire format directly. Flash does not gain a dependency on any gRPC/protobuf library, and
users who want a gRPC service framework build it on top of Flash rather than being handed one.

**Revisit when.** Not planned to be revisited; this is a scope boundary, not a temporary
limitation.

---

## DEC-09 — The chosen `Http2FrameWriter` design, with its benchmark numbers

**Status.** Not yet decided — this entry is a placeholder until Phase 3 runs its gate. Phase 3
benchmarks three candidate writer designs ((a) plain `ReentrantLock.lock()` per frame,
(b) `tryLock()` + intrusive MPSC, (c) a dedicated writer virtual thread fed by an MPSC queue)
against the numeric gate criteria in the plan (0 B/op and <50 ns overhead at N=1; ≥60% of the
N=1 per-thread aggregate throughput and <1 ms p999 at N=64; no carrier pinning). This entry is
filled in with the winning design and the raw numbers when Phase 3 completes, or with the
failure and the redesign taken if no candidate meets the gate.

**Revisit when.** N/A until Phase 3 lands.

---

## DEC-10 — `Upgrade: h2c` is deliberately **not** implemented

**Context.** RFC 7540 §3.2 (the original HTTP/2 RFC) defined an `Upgrade: h2c` mechanism to
move a plaintext HTTP/1.1 connection to HTTP/2 mid-connection. RFC 9113 (which obsoletes
RFC 7540) §3.1 removes this mechanism entirely from the current specification.

**Options.**
1. Implement `Upgrade: h2c` for compatibility with any client that still relies on it.
2. Do not implement it; support cleartext HTTP/2 only via prior knowledge (RFC 9113 §3.4).

**Decision.** Option 2.

**Consequence.** Every h2c client that matters for Flash's use case (gRPC, and every modern h2c
implementation) uses prior knowledge, not the upgrade dance, so nothing is lost in practice.
Recorded explicitly so a future contributor who notices `Upgrade: h2c` is unhandled does not
assume it was an oversight and add it back.

**Revisit when.** A concrete client that requires `Upgrade: h2c` and cannot be changed is
identified. Not anticipated.

---

## DEC-11 — Commit scope stays `core`; `h2` is not added to `AGENTS.md`'s allowed-scope list

**Context.** `AGENTS.md` (§Commit Messages) enumerates the allowed Conventional Commits scopes.
`h2` is not among them. R9 leaves the choice open: either add `h2` as a new scope via a
`docs:` commit, or use `core` and record the decision here.

**Options.**
1. Add `h2` as a new allowed scope, so h2-specific commits are distinguishable in history from
   other core work at a glance.
2. Use the existing `core` scope for all HTTP/2 work.

**Decision.** Option 2.

**Consequence.** All HTTP/2 commits use `feat(core): ...` / `fix(core): ...` /
`refactor(core): ...`, consistent with the branch name (`feature/core/http2`) and with `DEC-01`
(HTTP/2 is core, not a separate concern). A reader can still find every h2-related commit via
the file paths touched (`dev.relism.flash.h2/**`, `flash/docs/http2/**`) or via the commit body,
which is no worse than a scope label and avoids growing the scope list for what is, by `DEC-01`,
not actually a separate module.

**Revisit when.** The `h2` package's commit volume makes `core` too coarse to navigate in
`git log` — not expected before Phase 10 at the earliest, if ever.

---

## DEC-12 — Phase 1 plan corrections: two missing files, one corrected limit check

**Context.** While implementing Phase 1, two problems in the plan document itself surfaced
(distinct from problems in the *code*, which is what the `EX-nn` registry tracks).

1. Phase 1 task 8 requires "a `BufferedByteSource` owned by the connection that wraps the read
   buffer plus the socket and exposes `readByte()`, `readFully(...)`, `skip(...)` and `peek()`",
   and task 12 depends on it for h2c preface detection — but the Phase 1 **Files** list never
   named the file. Likewise, the typed rejection `EX-02`/`EX-03`/`EX-08`/`EX-18` all need (a
   specific HTTP status to respond with, as distinct from `HttpException`'s handler-routed
   semantics — see `DEC-14`) was never named as a file either.
2. Task 4's exact wording — "Enforce `MAX_REQUEST_LINE_LENGTH` against `headerEndIdx - base` for
   the request line specifically" — describes checking the length of the *entire header block*
   (`headerEndIdx` is where the whole header section ends), not the request line. The request
   line's own end is `protocolEnd` (or `sectionStart`), not `headerEndIdx`.

**Decision.**
1. Added `flash/src/main/java/dev/relism/flash/transport/BufferedByteSource.java` and
   `flash/src/main/java/dev/relism/flash/exceptions/MalformedRequestException.java` to Phase 1's
   Files list (see the phase section itself, now corrected in place).
2. Implemented the check as `protocolEnd - base > MAX_REQUEST_LINE_LENGTH` — the request line's
   actual span — rather than the literal (and, read literally, incorrect) `headerEndIdx - base`.

**Consequence.** None beyond the plan text now matching what was actually built and why —
these are wording/omission fixes, not design trade-offs. Recorded per the plan's own rule that
corrections to the plan must be explicit and tracked, never silent.

**Revisit when.** N/A — already resolved.

---

## DEC-13 — `BufferedByteSource`'s deadline is enforced by computing the exact remaining `SO_TIMEOUT` per underlying read, not by a fixed poll-and-retry loop

**Context.** `EX-07` requires an *absolute* deadline across a sequence of socket reads (a
per-read `SO_TIMEOUT` alone never trips against a peer that keeps each individual read within
the window while never completing the whole message — the canonical slowloris shape). Two ways
to implement that on top of the blocking `Socket`/`SSLSocket` API, which only offers a per-read
timeout:

**Options.**
1. Set `SO_TIMEOUT` to a fixed, short polling interval (e.g. 1 s); on each
   `SocketTimeoutException`, re-check whether the absolute deadline has actually passed, and if
   not, retry. Deadline precision is bounded by the poll interval (up to ~1 s of slop).
2. Before every underlying read, compute the exact remaining budget
   (`deadlineNanos - System.nanoTime()`) and hand that exact value to `setSoTimeout`. A
   `SocketTimeoutException` from that read then unambiguously means the deadline — not merely
   one poll cycle — has elapsed, with no retry loop needed.

**Decision.** Option 2.

**Consequence.** Deadline precision is exact (modulo OS timer granularity) rather than
poll-interval-bounded, and the implementation is simpler — no retry loop, no distinction between
"timed out this poll" and "timed out for real". The cost is one `setSoTimeout` syscall per
underlying fill (not per byte, not per `read()` call served from the buffer) — negligible, since
fills already happen at buffer granularity (up to 8 KiB at a time), not per byte.

**Revisit when.** Not expected to be revisited; this is strictly better than option 1 on both
precision and simplicity.

---

## DEC-14 — `MalformedRequestException extends HttpException`; caught separately from the per-request handler try/catch, never routed through the user's exception handler

**Context.** `EX-02`/`EX-03`/`EX-08`/`EX-18` all need to reject a request with a specific HTTP
status before any handler or middleware runs. `HttpException` already exists in this codebase
for "carry a status code, get turned into a response" — but it is caught by
`router.getExceptionHandler()` inside the per-request try/catch, which is user-configurable
(e.g. `flash-ext-jackson` installs a JSON-formatting handler).

**Options.**
1. Reuse `HttpException` directly, letting a malformed request flow through the same
   user-configurable exception handler as an application-level failure.
2. A new type, `MalformedRequestException extends HttpException`, caught at a separate site —
   around `parser.parse(in)` itself, before routing — with a fixed, minimal, non-customizable
   response, always followed by closing the connection.

**Decision.** Option 2.

**Consequence.** A malformed or hostile request never reaches user code at all — not the
handler, not middleware, not a custom exception handler that might (reasonably, for its actual
purpose) try to look up a route, log structured JSON, or otherwise do work that assumes a
well-formed `Request`. The connection is always closed afterwards, never kept alive, which is
exactly the property `EX-02`'s smuggling defense depends on. Subclassing `HttpException` (rather
than an unrelated new hierarchy) keeps `status()`/message` access idiomatic with the rest of the
codebase's error-status convention, while the distinct type is what lets `HttpServer` catch it
at the parse site specifically.

**Revisit when.** Not expected to be revisited.

---

## DEC-15 — Phase 2 plan correction: the "no `ThreadLocal` anywhere" DoD line was inconsistent with `EX-06`'s own phasing

**Context.** Phase 2's DoD stated flatly: "No `ThreadLocal` remains anywhere in `flash` core."
`EX-06`'s registry entry — the fix this DoD line is checking — explicitly phases itself:
"**Phase**: 2 (introduce), 3 (h2 consumes it), 4 (router consumes it)." `FastPathRouterImpl` and
`FastPathWsRouterImpl`'s `ThreadLocal`s (`MatchResult`, `MethodPathByteView`) are the "router
consumes it" part, assigned to Phase 4 — where the router also gains the scratch-parameter (or
request-context) API surface change needed to remove them correctly, per `EX-06`'s own fix
description ("the router now takes the scratch as a parameter or reads it from the request's
context"). Taken literally, Phase 2's DoD line would have required either doing Phase 4's router
work two phases early (undermining the reason `EX-06` was split across phases in the first
place — the router-facing API change is more invasive and deserves its own phase) or leaving the
DoD unresolvable.

**Options.**
1. Do the full router `ThreadLocal` removal now, in Phase 2, to satisfy the DoD line literally.
2. Correct the DoD line to match `EX-06`'s already-considered phasing, and record why.

**Decision.** Option 2.

**Consequence.** Phase 2 removes every `ThreadLocal` `HttpServer` itself owned (`SHA1`,
`LONG_BUF`, `STREAM_RELAY_BUFFER` — all now fields on `ConnectionScratch`). The router's two
`ThreadLocal`s are explicitly left for Phase 4, tracked there, not silently dropped — this is
still R10-compliant (the defect is registered and scheduled, not ignored) and keeps Phase 2
scoped to what it already set out to do (kill the `HttpServer` god class), rather than absorbing
an unrelated API-surface change under deadline pressure.

**Revisit when.** N/A — resolved; Phase 4 closes the remaining `EX-06` scope.

---

## DEC-16 — No separate `WebSocketFrameCodec` class; the `EX-11`/`EX-12` fixes stay inside `WebSocketSession`

**Context.** Phase 2's file list named `dev.relism.flash.websocket.WebSocketFrameCodec.java`,
extracted from `WebSocketSession`, as a Phase 2 deliverable — motivated by R6 (no god classes)
and by a forward reference in Phase 15 ("this requires abstracting its InputStream/OutputStream
pair behind a small interface — which the Phase 2 WebSocketFrameCodec extraction should already
have made possible").

**Options.**
1. Extract a `WebSocketFrameCodec` operating on byte arrays/scratch buffers, with
   `WebSocketSession` calling into it for encode/decode and owning only the actual stream I/O.
2. Keep frame encode/decode inside `WebSocketSession`, where it already lived.

**Decision.** Option 2, for this phase.

**Consequence.** `WebSocketSession` after the `EX-01`/`EX-11`/`EX-12` fixes is ~360 lines — over
R6's soft ~250-line guidance, but R6 itself carves out exactly this case: "a 300-line class that
is one cohesive state machine ... is fine; a 150-line class doing two things is not." Frame
header decode, continuation reassembly, and masking are one state machine (RFC 6455 §5's frame
grammar), not two unrelated responsibilities glued together, so the soft guidance's exception
applies. Splitting it now, before any concrete second caller exists, risks the "artificial
split that doesn't reduce complexity" R6 also warns against implicitly — there is no code today
that would consume a standalone codec except `WebSocketSession` itself. Phase 15's forward
reference is noted and re-evaluated then: if RFC 8441 (WebSocket over h2) genuinely needs frame
encode/decode decoupled from a socket-backed `InputStream`/`OutputStream` pair (an h2 stream is
not one), the extraction happens at that point, with a real second shape driving the interface
instead of a speculative one.

**Revisit when.** Phase 15, when RFC 8441's transport requirements are concrete.
