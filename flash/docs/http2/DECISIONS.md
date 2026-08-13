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

## DEC-01 — HTTP/2 lives in `flash` core, package `dev.relism.flash.http2`, not an extension

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
`dev.relism.flash.http2` and vice versa. The cost is more up-front extraction work in Phase 2 and
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

**Context.** Phase 3 is a GO/NO-GO gate: build and benchmark the connection-level serialized
frame writer, the one genuinely novel architectural risk in this codebase's HTTP/2 work (see
Part I's "one thread owns the socket" framing). Three candidate designs were built and compared
against the plan's numeric gate criteria: (a) `plain_lock` — unconditional
`ReentrantLock.lock()` per frame; (b) `trylock_mpsc` — `tryLock()` fast path with an intrusive
Vyukov-style MPSC queue fallback; (c) `dedicated_thread` — every write handed off via the same
MPSC queue to one dedicated, parked/unparked writer thread. A fourth harness,
`raw_unsynchronized` (no coordination at all — unsafe, not a candidate), establishes the N=1
baseline the 50 ns budget is measured against.

**Options.** (a), (b), (c) as above — full description, JMH methodology, and raw numbers in
`flash/docs/http2/WRITER.md`.

**Decision.** (b), `trylock_mpsc` — matching the plan's own proposed design. Measured against
every gate criterion (JDK 21.0.11, JMH 1.37; see `WRITER.md` for the complete methodology
including its two stated caveats — an in-memory counting sink rather than a real loopback
socket, and one JMH "op" being a 4 000-write burst rather than a single write):

| Criterion | Result | Verdict |
|---|---|---|
| N=1: 0 B/op | 0.0015 B/write differential vs. `raw_unsynchronized`, within measurement noise | PASS |
| N=1: ≤50 ns overhead vs. raw unsynchronized | 42.6 ns point estimate, ≤47.9 ns at the 99.9% CI's worst case | PASS |
| N=64: throughput ≥60% of N=1 per-thread rate | 65.5% | PASS |
| N=64: p999 <1 ms | 11.8–14.2 µs | PASS |
| No carrier pinning (`-Djdk.tracePinnedThreads=full`) | none observed | PASS |
| Stress test green at every N ∈ {1,2,8,64,256}, 1000 iterations, incl. parallelism=1 | 10 000/10 000 | PASS |

`plain_lock` was also measured for comparison (not merely asserted inferior): it retains only
58.1% of its own N=1 throughput at N=64 (below the 60% bar `trylock_mpsc` clears) and its p999
latency blows up to 1.6–2.0 ms under load — unfair blocking causing tail pile-up, exactly the
failure mode a naive per-frame lock predicts. `dedicated_thread` has the best tail latency of the
three (1.5–6.7 µs at N=64) but pays a ~3.3× throughput penalty at N=1, because every write —
even a genuinely uncontended one — pays a full park/unpark handoff; there is no fast path for
the dominant "one active writer" case. Neither alternative is a better shipped default than
`trylock_mpsc`.

**Consequence.** `Http2FrameWriter` ships exactly as designed in the plan: `tryLock()` fast path
(one uncontended CAS on the overwhelmingly common single-writer case), intrusive MPSC fallback
under genuine contention (the `WriteIntent` itself is the queue node — zero allocation to
enqueue), `ReentrantLock` throughout (never `synchronized` — `EX-01`'s carrier-pinning fix
generalized to the connection writer), and a scan-based write-timeout reaper
(`Http2Limits.WRITE_TIMEOUT_MS`, 30 s) rather than a per-write `System.nanoTime()` deadline — an
earlier revision recorded a per-write deadline and this phase's own benchmark is what caught it
costing enough to threaten the 50 ns budget, which is itself part of why the reaper's
consecutive-scan design (documented on `Http2FrameWriter.WriteTimeoutReaper`) exists. Phase 4 may
proceed.

**Revisit when.** Not expected to be revisited — the three-candidate comparison is unlikely to
change qualitatively unless the JDK's virtual-thread scheduler or `ReentrantLock` implementation
changes materially. If a future JDK's `synchronized` stops pinning carriers (JEP 491, JDK 24+),
revisit whether `synchronized`'s simpler semantics become preferable now that its only drawback
here is removed — but `ReentrantLock` still uniquely offers `tryLock()`, which this design's fast
path depends on, so the revisit is not expected to change the outcome.

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
the file paths touched (`dev.relism.flash.http2/**`, `flash/docs/http2/**`) or via the commit body,
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

---

## DEC-17 — `FrameWriterBenchmark` lives in `src/jmh/java`, a source root registered only inside the `jmh` profile, not in `src/test/java`

**Context.** The Phase 3 JMH benchmark (`FrameWriterBenchmark`) was first placed directly in
`src/test/java/dev/relism/flash/http2/frame/`, on the theory recorded in `flash/pom.xml`'s comment
at the time: since the class carries only `@Benchmark`/JMH annotations and no JUnit annotations,
Surefire's JUnit-Jupiter engine would simply not select it as a test, so a plain `mvn test` (no
`-Pjmh`) would harmlessly ignore it. Verifying this assumption (`mvn -pl flash -am clean
test-compile`, no profile) showed it is false: Surefire's `junit-jupiter` engine performs test
*discovery* by loading every class under `target/test-classes`, regardless of whether it
ultimately selects it as a test — and `FrameWriterBenchmark` cannot even compile without
`jmh-core` on the classpath (it imports `org.openjdk.jmh.annotations.*` unconditionally), so with
the `jmh` profile inactive the module's test-compile step failed outright: "package
org.openjdk.jmh.annotations does not exist". A plain `mvn test` on `flash` — the command every
other phase's DoD, and CI itself, uses to verify "still green" — was broken for the entire
module, not merely silently skipping the benchmark as intended. This was caught only because
this phase's resume step re-ran `mvn test` (via the maven-wrapper distribution under
`~/.m2/wrapper/dists`, not a bare `mvn` on `PATH`) without `-Pjmh`, rather than re-running the
`-Pjmh`-scoped command the prior session had been using — the same class of gap R10 exists to
catch, just in the build graph rather than the source graph.

**Options.**
1. Keep the benchmark in `src/test/java`, and instead exclude it from the default Surefire test
   set via `<excludes>` in the `maven-surefire-plugin` configuration, re-including it only when
   `-Pjmh` is active. This still leaves it on the default `test-compile` classpath, so the
   compile failure would remain — excludes only affect which already-compiled tests Surefire
   *runs*, not what the compiler plugin *compiles*. Rejected: does not fix the actual failure.
2. Move it to its own source root, `src/jmh/java`, and register that root as a test-source
   directory (`build-helper-maven-plugin`'s `add-test-source` goal) only inside the `jmh`
   profile's `<build>`. With the profile inactive, the file is not handed to the compiler at
   all, under any goal — not `test-compile`, not IDE indexing driven by the effective POM.
   This is also what the plan itself already suggested (Phase 3's Files list: `flash/src/jmh/
   java/dev/relism/flash/http2/FrameWriterBenchmark.java (or a flash-bench submodule...)`) — the
   prior session's placement in `src/test/java` was itself a deviation from the plan's own
   suggested layout, not a considered alternative.
3. A separate `flash-bench` submodule, depending on `flash` and always pulling in JMH. The
   plan's own text offers this as the other option, rejected for the same reason a `jmh` profile
   was chosen over it in the first place: a whole extra module (its own `pom.xml`, its own
   `groupId:artifactId`, its own place in the reactor) for one benchmark class is disproportionate
   machinery, and it does not obviously fix the underlying problem either — `mvn test` from the
   repo root still touches every reactor module and would still need the module's own default
   build to not require JMH.

**Decision.** Option 2 — matching the plan's original suggestion, which is exactly what should
have been done the first time.

**Consequence.** `mvn -pl flash -am test` (no profile) compiles and runs the ordinary unit/stress
tests only, exactly as every other phase's DoD assumes, and never touches JMH. `mvn -Pjmh -pl
flash test-compile` (or any goal at `generate-test-sources` or later, with the profile active)
additionally compiles `src/jmh/java` into `target/test-classes`, exactly where
`FrameWriterBenchmark`'s own Javadoc's run instructions already expected it, so that Javadoc
needed no change. `build-helper-maven-plugin` (`${build.helper.plugin.version}`, `3.6.0`) is a
new build-time-only dependency of the `flash` module, added to the root `pom.xml`'s
`<properties>` alongside `jmh.version`, consistent with how every other plugin version in this
reactor is centralized. No production code changed; this is a build-graph correction only.

**Revisit when.** Not expected to be revisited.

---

## DEC-18 — Phase 17 gains a second, explicitly non-gating category of benchmark: application-level, real-`HttpServer`, showcase/literature-only

**Context.** Raised while wrapping up Phase 3, after reviewing `FrameWriterBenchmark`'s results
with the project owner. Phase 3's benchmark is deliberately narrow — it exercises only
`Http2FrameWriter` against an in-memory `CountingSink`, isolating the writer's own lock/queue
cost from network variance (see `WRITER.md`'s stated caveats). That narrowness is correct for a
GO/NO-GO *component* gate, but it means nothing in the plan yet produces end-to-end, real-
`HttpServer` numbers — realistic traffic shapes, or deliberately extreme ones (thousands of
streams on one connection, pathological header blocks, slow/bursty clients, mixed h1+h2 on one
listener) — of the kind that make a project's performance claims concrete rather than asserted.
The project owner wants exactly this: **benchmark-driven development** as an ongoing practice,
not only a one-time gate, with results available for showcase and literature purposes
(illustrating real behavior under real and extreme conditions) independent of whether they pass
or fail anything.

**Options.**
1. Fold this into Phase 17's existing JMH suite (task 1) and its allocation/latency gates (tasks
   2–3), i.e. make these new benchmarks part of the same pass/fail pipeline as the rest of
   Phase 17.
2. Add it as a distinct, explicitly non-gating task within Phase 17 — same `src/jmh` source root
   as the Phase 3 writer benchmark, same JMH tooling, but no threshold, no CI wiring, output
   meant to be read by a human (or quoted in a doc/blog post), not consumed by a pass/fail check.

**Decision.** Option 2, recorded now as a scoped goal for Phase 17 (Phase 17's own Tasks list,
new task 8) — **not implemented as part of Phase 3 or this decision**. Phase 4 begins immediately
after this entry with a clean, unrelated scope.

**Consequence.** Phase 17, when it lands, produces two categories of benchmark under `src/jmh`,
and both must stay distinguishable at a glance (by class name, by package, or by a doc-comment
banner — decided when Phase 17 is actually implemented): (a) the gating suite — allocation-rate
and latency-regression checks that fail CI, matching this phase's existing tasks 1–3, run against
narrow, isolated scenarios exactly like `FrameWriterBenchmark`; and (b) the showcase suite —
real, end-to-end `HttpServer`/h2-connection scenarios, including deliberately extreme ones, that
only print results and never gate anything. Keeping (b) non-gating is deliberate: an "extreme
case" benchmark (e.g. 10 000 streams on one connection) is valuable precisely because it shows
*how* the system behaves under stress, including graceful degradation — turning that into a
pass/fail threshold would either be meaningless (no natural "correct" number for a pathological
case) or would quietly narrow what counts as an "extreme case" down to whatever currently passes.

**Revisit when.** Phase 17 is actually started — at that point this entry's task 8 becomes
concrete work with its own scenario list, harness design, and output format, rather than a
recorded intention.

---

## DEC-19 — `EX-06`'s router half is fixed with an opaque, caller-owned per-connection scratch object, not by extending `ConnectionScratch`

**Context.** `EX-06`'s registry entry phases itself: "Phase 2 (introduce), Phase 3 (h2 consumes
it), Phase 4 (router consumes it)" — Phase 4 is where `FastPathRouterImpl`'s and
`FastPathWsRouterImpl`'s `ThreadLocal<MatchResult>`/`ThreadLocal<MethodPathByteView>` (unbounded
under virtual threads, one per connection with no upper bound and no pooling — exactly the
failure mode `ConnectionScratch` exists to avoid for every other per-connection buffer) get
removed. `ConnectionScratch`'s own class Javadoc (written in Phase 2, in anticipation) already
commits to a specific mechanism: "Extended in Phase 4 with the router's reusable
{@code MatchResult}/path-view fields."

Attempting that literally surfaced a real problem: `ConnectionScratch` lives in
`dev.relism.flash.transport`; the router lives in `dev.relism.flash.routing` (and
`dev.relism.flash.routing.routers.fastpathrouter`). Today `transport` depends on `routing`
(`ConnectionContext` holds `AbstractRouter`/`AbstractWsRouter`) but **`routing` has zero imports
of `transport`** anywhere in this codebase (verified by grep, not assumed) — a clean one-way
dependency. Adding the router's scratch fields to `ConnectionScratch` and passing it into
`route()` would require `routing`'s classes to import `transport.ConnectionScratch`, creating the
first reverse edge and a genuine package cycle where none exists today.

**Options.**
1. Extend `ConnectionScratch` as its own Javadoc already describes, accepting the new
   `routing → transport` edge (and the resulting cycle with the existing `transport → routing`
   edge).
2. `AbstractRouter`/`AbstractWsRouter` gain a `newScratch()` method (default `null`) that each
   router implementation overrides to return an opaque, implementation-specific object (kept as a
   package-private nested class — `FastPathRouterImpl.RouteScratch`,
   `FastPathWsRouterImpl.RouteScratch` — never a new public type). The connection driver
   (`Http1Connection.run`) calls `newScratch()` **once per connection**, exactly the same
   "created once, held by the loop, reused across every request" shape already used there for
   `RequestParser`, and passes the opaque result into every `route(request, scratch)` call for
   that connection's lifetime. No package outside `routing`/`routing.routers.fastpathrouter` ever
   sees the concrete scratch type.

**Decision.** Option 2.

**Consequence.** Practically identical outcome to option 1 — one object per connection, created
once, reused across every request on that connection, replacing the `ThreadLocal`s — but without
introducing `routing`'s only dependency on `transport`. `ConnectionScratch`'s own Javadoc (which
predated this decision) is corrected in the same change to describe what was actually built
rather than the mechanism it originally assumed; `AbstractRouter.route`'s and
`AbstractWsRouter.route`'s signatures gain an `Object scratch` parameter, which is the one
API-surface cost of this approach (every router implementation, and every direct caller —
`Http1Connection` and the handful of tests that call `route()` directly — must now pass one).
`EX-19` (reusable `PathParams`/path-param arrays) piggybacks on the same `RouteScratch` object
for `FastPathRouterImpl`, since it needed an identical "created once per connection, grown to the
connection's high-water mark" lifetime — implemented together with `EX-06`'s router half rather
than as a separate pass over the same class.

**Revisit when.** Not expected to be revisited — the untyped `Object scratch` parameter is a
minor wart, but the alternative (a generic `AbstractRouter<S>` type parameter propagated through
`ConnectionContext`, `ServerHandle`, and every public router-registration API) is a far larger
API-surface change for one internal implementation detail, and is not justified unless a second
router implementation actually needs a differently-shaped scratch object — none exists today.

---

## DEC-20 — Phase 4 performance measurements: `EX-04`, `EX-33`, the router's own allocation profile, and the h1 zero-alloc contract's actual current number

**Context.** Phase 4's plan carries two explicit "measure, keep only if it earns its keep"
instructions (`EX-04`: revert if the win is negative or noise; `EX-33`: keep scalar if the SWAR
win is under 3%), plus a zero-alloc contract ("an h1 `GET /users/{id}` request that reads three
headers and one path param must be 0 B/op end to end except for the user-facing `String`s the
handler explicitly asks for. Add this as a JMH allocation test now"). All three measured together
(JDK 21.0.11, JMH 1.37, `avgt` mode, `-prof gc`, `flash/src/jmh/java`) rather than as separate
passes, since they share the same request/route fixtures.

**Measurements.**

*`EX-33` — SWAR vs. scalar `\r\n\r\n` scan, realistic ~330-byte request (`ByteScanBenchmark`):*

| | ns/op |
|---|---|
| `headerEndScan_scalar` | 134.921 ± 5.558 |
| `headerEndScan_swar` | 87.116 ± 1.411 |

SWAR is **35.4 % faster** (47.8 ns absolute) — far above the 3 % keep-threshold. **Kept.**

*`EX-04` — the `longAt`/`ByteCompare` mechanism in isolation, and the real router
(`FastPathRouterBenchmark`):*

| | ns/op | B/op |
|---|---|---|
| `byteCompare_byteAtATime` (useLong=false) | 22.281 ± 1.021 | ≈0 |
| `byteCompare_longPath` (useLong=true) | 15.146 ± 1.090 | ≈0 |
| `router_staticRoute` (real `FastPathRouterImpl.route`) | 143.409 ± 14.992 | 0.001 |
| `router_parametricRoute` (real `FastPathRouterImpl.route`, 1 param extracted) | 284.433 ± 31.510 | 0.002 |

The long path is **32.1 % faster** (7.1 ns) than the byte-at-a-time comparison it replaces, at
the mechanism level — a clear, real win, confirming `EX-04` is worth keeping. **Honest caveat**,
not a failure of the measurement but a finding in its own right: `router_staticRoute`/
`router_parametricRoute` do **not** exercise this win today, because the actual value
`FastPathRouterImpl.route` passes to `router.match()` is always a
`FastPathViews.MethodPathByteView` — a deliberate composite of method bytes + path view, which
(per `EX-04`'s own registry text) correctly keeps `supportsLong() == false`, since a word-at-a-
time read across two independent sources is unsound, not merely unoptimized. `EX-04`'s win will
apply once a future phase (`HPACK` static-table matching, frame validation — Phase 5+) compares
two genuinely-contiguous array-backed ranges directly, which is exactly the shape
`byteCompare_longPath` measures. **Kept** — implemented correctly, verified correct
(`FastPathViewsLongAtTest`), and measured worthwhile for its actual future consumers; it was
never going to show up in today's router-benchmark numbers, and the plan's own text already
predicted this by excluding `MethodPathByteView` from the fix.

Separately: both router benchmarks show **≈0 B/op** — confirms `EX-06`/`EX-19`'s scratch reuse
(the `RouteScratch` object, its reused `MatchResult`, `MethodPathByteView`, and path-param
arrays/`PathParams` instance) is genuinely zero-allocation in practice, including on a
parametric route that extracts a param.

*The h1 zero-alloc contract, end to end (`RequestPipelineBenchmark`):*

| | ns/op | B/op |
|---|---|---|
| `parseAndRoute` (parse + route only, no header/param access) | 1135.125 ± 68.888 | 120.008 |
| `parseRouteAndExtractThreeFields` (+ 1 path param, 2 headers read) | 1335.965 ± 57.378 | 304.009 |

**Not literally 0 B/op** — and this is expected, not a Phase 4 regression: the 120.008 B/op in
`parseAndRoute` (which touches no header or path-param API at all) is entirely attributable to
`Request`/`RequestBody`/`RequestLine` construction, still allocated fresh per request. That is
`EX-21`/`EX-22`'s scope, explicitly assigned to **Phase 6** ("Request/Response model refactor"),
not Phase 4's. The delta to `parseRouteAndExtractThreeFields` — 304.009 − 120.008 = **184.001
B/op for exactly three explicit `String` reads** (one path param, two headers) — is precisely the
"user-facing `String`s the handler explicitly asks for" the contract's own text carves out as
acceptable, and confirms that *reading* those three fields (the header index lookup, the pooled
slice, the path-param array read) itself adds no allocation beyond the unavoidable `String`
objects themselves.

**Decision.** `EX-33`: keep the SWAR scan. `EX-04`: keep the `longAt`/`supportsLong`
implementation as built — correct, tested, and measured worthwhile for the array-backed
comparisons it was designed for, independent of whether today's single call site
(`MethodPathByteView`) happens to use it. The h1 zero-alloc DoD item is recorded as: **Phase 4's
own scope (`EX-05`/`EX-09`/`EX-19`/`EX-25`/`EX-26`/`EX-33`) is verified zero-allocation**
(`router_staticRoute`/`router_parametricRoute`'s ≈0 B/op, `HeaderMapIndexTest`'s identity-based
allocation check); the remaining 120.008 B/op is `Request`/`RequestBody`/`RequestLine`
construction, out of scope until Phase 6, and is not silently hidden — this benchmark now exists
specifically so Phase 6 has a "before" number to compare against and a regression gate once
Phase 17 wires `-prof gc` into CI.

**Consequence.** No code changes from this entry — it is a measurement record. Three new
benchmark classes ship under `src/jmh/java`: `ByteScan`Benchmark, `FastPathRouterBenchmark`,
`RequestPipelineBenchmark` — all component-level and gate-relevant (unlike the `DEC-18` showcase
category, these exist to answer the plan's own explicit measurement instructions, not for
literature/demo purposes).

**Revisit when.** `RequestPipelineBenchmark`'s `parseAndRoute` number should drop close to 0 B/op
once Phase 6 lands `Request`/`RequestBody` pooling — re-run this exact benchmark then and update
this entry (or add a new one) with the "after" number, closing the loop Phase 4 opened.

---

## DEC-21 — Phase 5's zero-alloc contract, measured

**Context.** Phase 5's plan states: "Reading, validating and discarding a frame: 0 B/op ...
Writing a frame header: 0 B/op." Measured with JMH `-prof gc` (JDK 21.0.11, JMH 1.37,
`FrameLayerBenchmark`, `src/jmh/java`) rather than left as an unverified assertion, per this
project's own standing practice of measuring every stated performance/allocation claim
(`DEC-09`, `DEC-20`).

**Measurement.** `readValidateAndDiscard` (`Http2FrameReader.readFrame` +
`FrameValidator.validate` + one byte read from the payload + `consumeFrame`, against a warm,
already-grown buffer, matching real keep-alive-connection steady state): 299.846 ± 19.722 ns/op,
**0.002 B/op** — indistinguishable from zero (compare `DEC-20`'s harness-floor discussion: even
this near-zero figure is most plausibly measurement noise around the true 0, not a real
allocation, since nothing in the read/validate/consume path can be shown by inspection to
allocate on the warm path). `writeFrame` (`FrameWriteBuffer.beginFrame` + one `writeBytes` call +
`endFrame`, against an already-grown `ByteWriter`): 14.262 ± 1.084 ns/op, **≈10⁻⁴ B/op** —
likewise indistinguishable from zero.

**Decision.** Contract verified as stated; no design change required. Both numbers are recorded
here as the baseline Phase 17's eventual CI allocation gate should hold this component to.

**Consequence.** None beyond the recorded numbers — this entry exists so a future regression
(e.g. a later phase accidentally introducing an allocation on this path while adding HPACK or
stream-state integration) has a concrete "was 0, now isn't" baseline to diff against, per this
project's standing insistence that every non-obvious performance claim trace to an actual number.

**Revisit when.** Not expected to be revisited; re-measure if `FrameHeader`, `Http2FrameReader`,
or `FrameWriteBuffer` are ever modified in a way that could plausibly affect their allocation
profile.

---

## DEC-22 — `HeaderMap` splits into `HeaderView` (interface) + `Http1HeaderMap` (impl, staying in `models`, not moving to `http1`)

**Context.** Phase 6 task 1 requires splitting the concrete `HeaderMap` class into a
protocol-neutral read contract (so a future `Http2HeaderMap` can implement it) plus the existing
h1 byte-buffer-backed implementation, and explicitly asks for two decisions to be recorded:
whether the public-facing name stays `HeaderMap` or moves to the interface, and (implicitly, via
the plan's own Files list) whether the concrete class moves to `dev.relism.flash.http1`.

**Decision 1 — naming.** Checked whether `HeaderMap` is actually part of `Request`'s public
surface first, since the task's hard constraint is "the public API of `Request` must not
change": `Request`'s own methods (`header`, `headers`, `param`, `query`) return `String`/
`List<String>`, never a `HeaderMap`/`HeaderView` — the only exposure is the transitive,
Javadoc'd-as-"Internal" `Request.getRequestLine().getHeaders()` path. Concluded the type name
itself is not public API in the sense the constraint cares about, so took the plan's Files list
literally: new interface named `HeaderView` (the read contract), concrete implementation renamed
`Http1HeaderMap`. `RequestLine.headers` (and its Lombok-generated `getHeaders()`) is now typed
`HeaderView`.

**Decision 2 — package placement.** The plan's Files list suggests `http1/Http1HeaderMap.java`.
Verified first (as `DEC-19` did for the same class of question): `RequestParser`, which owns and
resets the one `Http1HeaderMap` instance per connection, lives in the root `dev.relism.flash`
package, not `http1`. `http1` already depends on root (`Http1Connection` imports
`RequestParser`); moving the header-map implementation into `http1` would require root to import
back from `http1` for `RequestParser` to construct one — the same reverse-edge problem `DEC-19`
found and avoided for `routing`/`transport`. Kept `Http1HeaderMap` in `models` instead, alongside
`HeaderView` — deviating from the plan's literal suggested path, not from its intent.

**Consequence.** `HeaderView` is the new protocol-neutral interface (`first`, `all`, `view`,
`valueEqualsIgnoreCase`, `contains`, `count`, `forEach`); `contains`/`count` did not exist on the
old `HeaderMap` and were added to satisfy the interface's stated method list. `Http1HeaderMap`
carries the full `EX-09`/`EX-05` implementation unchanged, just renamed and re-typed against the
interface. Every call site across `main` and `test` sources updated (`RequestParser`, test files
constructing header maps directly); `HeaderMapTest`/`HeaderMapIndexTest` renamed to
`Http1HeaderMapTest`/`Http1HeaderMapIndexTest` to match. 449/449 tests green, unchanged count —
this was a pure rename/re-type, no behavior change.

**Revisit when.** Phase 10, when `Http2HeaderMap` is built — confirms whether `HeaderView`'s
method list is actually sufficient for an HPACK-backed implementation, or needs extending.

---

## DEC-23 — Phase 6 closes `DEC-20`'s revisit loop: the h1 zero-alloc contract, re-measured after `Request`/`RequestBody`/`RequestLine`/`Response` pooling, plus one more allocation found and fixed (`EX-42`)

**Context.** `DEC-20` (Phase 4) measured `RequestPipelineBenchmark.parseAndRoute` at 120.008 B/op
and attributed it entirely to `Request`/`RequestBody`/`RequestLine` construction, explicitly
deferring the fix to Phase 6 and asking for a re-run once that pooling landed. Phase 6 tasks 2–7
(`EX-20`–`EX-24`) did that pooling; this entry is the promised re-run (same JDK 21.0.11, JMH 1.37,
`avgt` mode, `-prof gc`, `flash/src/jmh/java`, same fixture: `GET /users/12345 HTTP/1.1` with
`Host`/`Accept`/`Authorization`).

**First re-run, after `EX-20`–`EX-24` alone:**

| | ns/op | B/op |
|---|---|---|
| `parseAndRoute` | 1194.105 ± 944.469 | 48.008 |
| `parseRouteAndExtractThreeFields` | 1324.679 ± 296.883 | 232.009 |

Down from 120.008 to 48.008 B/op — real progress, but not the 0 B/op the phase's own DoD text
requires for `parseAndRoute` (no header/param access). Investigated rather than accepted: reading
`RequestParser.parse` line by line turned up three `new FastPathViews.RequestByteView(...)`
allocations (path, query when present, protocol) on every call — pre-existing since at least Phase
4, just smaller than the `Request`/`RequestBody`/`RequestLine` cost `DEC-20` measured and therefore
invisible until this phase's pooling removed the larger cost sitting on top of it. Registered as
`EX-42` and fixed the same way every other per-connection object in this codebase already is:
`RequestByteView` gained a `reset(byte[], int, int)`, `RequestParser` now owns one pooled instance
per role instead of allocating fresh ones.

**Second re-run, after `EX-42`:**

| | ns/op | B/op |
|---|---|---|
| `parseAndRoute` | 1111.260 ± 104.692 | 0.008 |
| `parseRouteAndExtractThreeFields` | 1301.840 ± 228.068 | 184.009 |

`parseAndRoute` — 0.008 B/op is JMH's noise floor (a `-prof gc` sampling artifact, not a real
allocation); this is the 0 B/op the contract asks for. `parseRouteAndExtractThreeFields` dropped
from 232.009 to 184.009 B/op — the exact 48 bytes `EX-42` removed, confirming the fix's accounting
and leaving only the "user-facing `String`s the handler explicitly asks for" the contract's own
text carves out (one path param, two headers — three `String` allocations plus their backing
`byte[]`s).

**Decision.** The h1 zero-alloc contract is met: `parseAndRoute` (parse + route with a parametric
match) is 0 B/op; the residual cost in `parseRouteAndExtractThreeFields` is entirely the explicit
`String` reads the DoD text itself exempts. `DEC-20`'s revisit item is closed.

**Consequence.** `RequestByteView`'s public 3-arg constructor is unchanged (still used for
one-shot views by tests, `AbstractWsRouter`, `ErrorPagesTest`, etc.) — only `RequestParser`'s three
call sites moved to the pooled `reset()` path. `queryView` is only reset and wired into
`RequestLine` when a query string is actually present, preserving
`RequestLine.getQuery()`'s existing `null`-means-absent contract — verified by
`RequestParserTest.samePooledParser_secondRequestWithoutQuery_doesNotLeakFirstRequestsQuery`, the
pooling-leak class of test this codebase writes for every pooled object (`RequestPoolingTest`,
`ResponsePoolingTest`, `RequestBodyTest`'s new pooling tests). 500/500 tests green.

**Revisit when.** Never expected to — this closes the loop `DEC-20` opened. If a future phase adds
a fourth per-request view (e.g. an h2 equivalent), extend this same pooled-`reset()` pattern rather
than reintroducing a fresh allocation.

---

## DEC-24 — Compact the HPACK arena and copy decoded headers into stream-owned storage

**Context.** Dynamic-table entries must be contiguous for cheap indexed lookup, but FIFO eviction
leaves holes at the front of a bounded arena. Views into that arena also cannot outlive later
decodes on a multiplexed connection.

**Decision.** Compact live dynamic entries when the free tail cannot hold an insertion. Do not use
`SegmentedByteView` for wrapped entries or CONTINUATION fragments. At the decoder boundary,
`HpackHeaderBlock` copies fields into a reusable arena owned by the stream.

**Consequence.** Compaction is occasionally O(table size), bounded by the advertised table size,
while all ordinary lookups and consumer copies remain contiguous. Stream handlers never observe
dynamic-table eviction or compaction. The JMH decode benchmark remains at the allocation noise
floor (0.001 B/op).

**Revisit when.** Profiling shows compaction is material under realistic dynamic-table churn.

---

## DEC-25 — Keep response-dependent h2spec gates with the phases that own the response path

**Context.** The Phase 8 checklist names whole h2spec sections 4 and 6.9, but several tests in
those sections require a successful response HEADERS/DATA sequence or per-stream flow-control
state. Those mechanisms are explicitly introduced in Phases 9–11. Making the whole sections green
now would require a temporary response/stream implementation in the connection state machine and
then deleting it immediately.

**Decision.** Phase 8 closes on every connection-owned h2spec case plus the complete unit,
integration, curl and allocation gates. Response- and stream-dependent cases remain visibly
unchecked and move with their owning Phase 9–11 gates. No placeholder response path is added.

**Consequence.** The connection layer stays cohesive: it validates frames and HPACK composition but
does not acquire a second, short-lived implementation of response or stream semantics. The ledger
records the partial external gate rather than claiming whole-section conformance prematurely.

**Revisit when.** Close the remaining h2spec section 4 and 6.9 cases as Phases 9–11 land, then rerun
the combined selection without skips.

---

## DEC-26 — Keep one protocol-neutral `PreEncodedHeader` model

**Context.** The original work plan proposed a second HTTP/2-specific `PreEncodedHeader` carrying
complete HTTP/1 and HPACK renderings. The existing public model already preserves immutable name
and value bytes, which is the common information both writers need. Adding another type would
split one application concept across protocol packages and force callers or `Response` to retain
protocol-specific state.

**Decision.** Keep `models.PreEncodedHeader` as the only public type. HTTP/1 renders its bytes as a
field line; HTTP/2 feeds the same byte ranges to the stateless encoder. Closed framework constants
(status, content type and Date) retain their specialized precompiled HPACK forms because those are
owned internally and measurably avoid work on every response.

**Consequence.** Application and middleware code builds one reusable header constant that works on
both protocols. Custom constants still traverse the HPACK literal encoder, but the measured write
path remains allocation-free and avoids duplicating the response model.

**Revisit when.** Only if profiling shows custom constant encoding is material; optimize the
existing model internally without introducing a second public header abstraction.

---

## DEC-27 — Drain already-buffered frames before dispatching completed streams

**Context.** A client can write a burst of complete requests before the server schedules their
handlers. Dispatching after every individual HEADERS frame lets a very fast handler close and
release streams while the same inbound burst is still being decoded, making the advertised
concurrency limit dependent on virtual-thread scheduling. Waiting a fixed interval would make the
limit deterministic but would add latency to every ordinary request.

**Decision.** Completed bodyless streams enter a fixed queue bounded by
`MAX_CONCURRENT_STREAMS`. The demultiplexer continues only while its own frame reader already has
bytes buffered; as soon as consuming the next frame would require network input, it drains the
queue to the shared virtual-thread executor. The configured concurrent-stream limit is 64 and the
primitive stream table has exactly the same bound.

**Consequence.** One socket read's request burst is admitted and bounded as a unit, excess streams
receive `REFUSED_STREAM`, and a single request is dispatched immediately without a timer. The demux
still never executes application code or waits for a worker. h2spec's concurrency case passes and
the lifecycle benchmark remains at the allocation noise floor.

**Revisit when.** If production traces show a materially different batching pattern, tune the
advertised limit or reader size from measurements; do not add a sleep-based dispatch delay.

---

## DEC-28 — Align the receive window with a coalescing bounded DATA pool

**Context.** The demultiplexer cannot block waiting for an application handler, but delaying
WINDOW_UPDATE only provides backpressure after the peer has spent the window it already owns. A
pool smaller than that outstanding credit can be exhausted legitimately. Allocating one buffer per
DATA frame is also unsafe because many tiny or heavily padded frames can consume little payload
storage while exhausting an object-per-frame pool.

**Decision.** Advertise 1 MiB at both the connection and stream receive levels and back the
connection with exactly 64 reusable 16 KiB buffers (also 1 MiB). Adjacent DATA payloads coalesce
into available tail space; padding contributes to flow credit but not storage. WINDOW_UPDATE is
sent at half-window consumption, never merely on receipt. Bodies at or below 64 KiB with a known
length use one reusable contiguous stream buffer and dispatch at END_STREAM; all other bodies
dispatch immediately onto the same protocol-neutral `RequestBody` over a blocking pooled source.

Response DATA uses the same serialized writer with a progress cursor. A stream object itself is
the executor task for initial handling and resumptions, so no per-resume closure is created.
WINDOW_UPDATE only schedules work; it never reads an application `InputStream` on the demux thread.

**Consequence.** Outstanding peer credit and worst-case pooled payload storage match exactly,
small frames do not multiply objects, slow consumers withhold credit naturally, and streaming
responses resume without recursive writer completion or demux blocking. The 100 MiB bidirectional
integration test remains bounded, while JMH measures the streaming request and response paths at
the allocation noise floor.

**Revisit when.** Production memory/throughput measurements justify a different window. Change the
window and pool byte capacity together; never raise credit independently of bounded storage.

---

## DEC-29 — Keep TLS HTTP/2 opt-in until the compliance gate

**Context.** Trailers and push streaming make the protocol feature-complete for ordinary and gRPC-
shaped traffic, but the dedicated rate-based and composite abuse controls are deliberately owned
by the following security phase.

**Decision.** Keep `FlashConfiguration.http2Enabled` defaulting to `false`. Phase 14 separates
cleartext behind its own `http2CleartextEnabled` opt-in, also defaulting to `false`. Passing the
hostile-peer gate removes the security blocker, but changing the TLS default remains deferred
until the complete external conformance gate is green.

**Consequence.** Existing deployments do not silently expose a newly completed protocol before its
adversarial gate. This is rollout sequencing, not an architectural separation: both protocols use
the same public request/response, header, trailer and streaming APIs.

**Revisit when.** At Phase 16 closure, after the external compliance matrix is green.

---

## DEC-30 — Rate-limit aggregate non-progress work as one class

**Context.** SETTINGS, PING, PRIORITY, WINDOW_UPDATE, empty DATA and unknown extension frames have
different wire semantics but share the abuse property that they can consume parser/control work
without advancing an application message. Separate limits leave gaps when an attacker alternates
frame types below every individual threshold.

**Decision.** Keep dedicated lower limits for mandatory SETTINGS and PING replies, plus one
connection-owned two-bucket counter for the aggregate non-progress class. RST_STREAM and stream
creation retain dedicated CVE-2023-44487 counters because their expensive effect is stream
lifecycle churn, not merely frame parsing.

**Consequence.** Mixed floods are bounded without six timers or maps. All counters are fixed fields
on the connection, use `System.nanoTime()`, allocate nothing per increment and require no reaper
thread. A fixed control-intent pool and one-in-flight intent per live stream bound write queues.

**Revisit when.** Production telemetry shows legitimate control-heavy traffic approaching the
aggregate default; tune the threshold from evidence without splitting the defence by frame type.

---

## DEC-31 — Keep the upstream HTTP/2 client proxy-oriented and single-owner

**Context.** A general-purpose HTTP client would introduce a second large public API, redirect,
cookie, authentication and retry policy, while the immediate requirement is a reliable Flash
reverse-proxy hop with trailers.

**Decision.** Pool one reusable connection per origin and serialize exchanges on that connection.
Reuse the core frame reader/writer and HPACK codec, but keep response assembly and ownership inside
the client connection. Expose `HttpProxy.toHttp2` as the protocol-neutral adapter and one shared
`HopByHopHeaders` policy for every conversion direction.

**Consequence.** HPACK and socket state have one clear owner, upstream connections are reused, and
trailer semantics cannot diverge by downstream protocol. Concurrent calls to one origin queue
behind its active exchange rather than pretending this minimal client is a fully multiplexed
general-purpose stack.

**Revisit when.** Proxy production traces show per-origin serialization is a bottleneck; add a
bounded pool or client-side multiplexing without changing the proxy-facing API.

---
