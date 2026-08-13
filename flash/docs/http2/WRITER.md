# The Serialized Frame Writer (Phase 3 — GO/NO-GO gate)

Audience: contributors. This is the design record and benchmark evidence for
`dev.relism.flash.http2.frame.Http2FrameWriter`, the one component every HTTP/2 write in this
codebase passes through. Phase 3 of `IMPLEMENTATION-PLAN.md` treats this component as the
single genuinely novel architectural risk in the whole project — everything downstream (frames,
HPACK, flow control) is table-driven work with known cost, but nothing in Flash today
coordinates concurrent writers onto one socket. If this component could not deliver, the plan
says to stop here having spent one phase, not ten. It delivered: **GO**, see the gate table at
the end of this document.

## The problem, precisely

Under HTTP/1.1, one virtual thread owns one connection's socket for the request/response it is
currently serving; there is never a second writer. Under HTTP/2, N streams share one connection
and their frames must interleave on the wire, so every write must pass through a serialization
point that plain HTTP/1.1 never needed. A lock taken naively per frame — `synchronized` or an
uncontended `ReentrantLock.lock()` — costs more per write than every allocation this codebase has
ever saved elsewhere (`EX-04` through `EX-29`), because it sits on the one path every response,
of either protocol width, eventually goes through.

## The design, three layers

**Layer 1 — serialize outside the lock.** By the time `Http2FrameWriter.write(WriteIntent)` is
called, the caller (a stream, or a connection-level singleton such as a precompiled SETTINGS ACK)
has already built its complete frame — header, HPACK block, payload — into a buffer it owns. The
writer never serializes anything; it holds the lock only for the duration of one bulk
`sink.write(buffer, offset, length)` call, never for a sequence of small writes. This is why
`EX-27` (collapsing `HttpServer.writeResponse`'s ~10 small writes into one) is a prerequisite for
h1 too, landing in Phase 6.

**Layer 2 — `ReentrantLock`, never `synchronized`.** On Java 21, a virtual thread that blocks
inside a `synchronized` block pins its carrier platform thread (JEP 491, which removes this,
only lands in JDK 24+). Blocking on a `ReentrantLock` unmounts the virtual thread instead. This
is the same fix `EX-01` applies to `WebSocketSession`, generalized to the connection writer where
it matters far more (N streams instead of one WebSocket session). `ReentrantLock` is load-bearing
for a second reason `synchronized` cannot offer: `tryLock()`.

**Layer 3 — `tryLock()` fast path, intrusive MPSC fallback.** The overwhelmingly common instant,
even on a genuinely multiplexed connection, has exactly one stream wanting to write: a browser
calling one API endpoint, a gRPC unary call. `tryLock()` on an uncontended lock is one successful
CAS; the calling thread writes inline and releases — no handoff, no queue touched, no allocation,
no context switch. Only when `tryLock()` fails — genuine contention, genuine multiplexing — does
the intent get published through `IntrusiveMpscQueue` (one more CAS, still zero allocation: the
`WriteIntent` itself is the queue node, via `mpscNext()`/`setMpscNext`) for the current lock
holder to drain.

```
happy path (1 active writer):  tryLock → sink.write → unlock              ≈ 1 CAS
contended  (N active writers): tryLock fails → CAS enqueue → return
                               current holder drains the queue before unlocking
```

### Why the fast path checks `queue.hasWork()`, not just `tryLock()`

Found by this phase's own stress test at N=64/256 — exactly the class of bug R10 exists to catch
before it ships, not after. Writing an intent immediately, ahead of anything already queued, is
only safe when nothing is already queued. Without the `hasWork()` guard:

1. Producer P calls `write(a)`, then `write(b)`. Both contend (someone else holds the lock) and
   both get queued — fire-and-forget from P's point of view.
2. The current holder is *about* to drain them but has not yet done so.
3. P's very next call, `write(c)`, finds the lock free (the holder released it between P's calls)
   and — without the guard — would write `c` directly, landing it on the wire *before* `a` and
   `b`, which are still sitting in the queue.

`write()` therefore checks `!queue.hasWork() && lock.tryLock()` before taking the direct path:
"bypass the queue" only happens when the queue is observed genuinely empty, i.e. everything any
producer has ever offered has already been written. `hasWork()` never false-negatives (it would
only ever wrongly report work that isn't there, which just costs an extra harmless `tryLock()`
attempt), so this preserves per-producer ordering without adding a false rejection of the fast
path.

## Lost-wakeup avoidance

The classic hazard for a design like this: a producer offers its intent to the queue at the exact
moment the current lock holder has just found the queue empty and is about to unlock. Without
care, the item is stranded — offered, but nobody left to drain it, and the producer already
returned believing the write is in flight.

```
Producer P                              Holder H (currently draining, about to unlock)
───────────                             ──────────────────────────────────────────────
                                         next = queue.poll()   // null: queue looks empty
queue.offer(intent)     ← races here →
if (lock.tryLock())                     lock.unlock()
    drive(null)          // P's own second chance: if P wins the tryLock() race
                          // immediately after H's unlock(), P itself becomes the new
                          // holder and drains — including its own just-offered intent.
```

Two cooperating mechanisms close this, and both are required — neither alone is sufficient:

1. **The producer's own second chance.** After a failed `tryLock()`, `write()` offers the intent
   *then* immediately attempts `tryLock()` again. If H has already unlocked by this point, P wins
   the second `tryLock()` and drains the queue itself (`drive(null)` — draining whatever is
   queued, which necessarily includes the intent P just offered, since `offer()` had
   already completed).
2. **The holder's re-check-after-unlock loop**, in `drive()`: after `unlock()`, re-read
   `queue.hasWork()`. If non-empty, attempt `tryLock()` again and drain, then unlock and re-check
   once more — looping, because this recheck cycle can itself race the same way a first pass can.
   If a second `tryLock()` in this loop fails, some *other* thread now holds the lock, and by the
   same argument that other holder's own re-check-after-unlock covers the item once it releases.

The correctness argument for why together these are sufficient is a happens-before chain through
the queue's `AtomicReference` (`IntrusiveMpscQueue.head`, a `getAndSet` per `offer`) and the
lock's own acquire/release ordering: every `offer()` happens-before some subsequent `poll()` that
observes it (directly, or via the momentary-`null` self-correcting race documented on
`IntrusiveMpscQueue` itself — see its class Javadoc), and every thread that successfully offers
either (a) is itself about to attempt `tryLock()` and, on success, drains everything including its
own offer, or (b) fails that `tryLock()`, meaning some other thread holds the lock *at that
instant* and that thread's own unlock will trigger its own re-check-after-unlock loop. There is no
interleaving in which an offered intent is neither drained by its own producer nor covered by some
other thread's re-check loop.

A frame's bytes are never interleaved with another frame's bytes: every write of one intent is a
single `sink.write` call issued while holding the lock, and the lock is not released between a
`WriteIntent`'s bytes — proven directly by `Http2FrameWriterStressTest`, which reassembles
producer/sequence/marker-tagged frames from the sink's output and fails loudly on any torn,
duplicated, reordered, or lost frame.

## Write timeout

A blocking write is unavoidable when the kernel send buffer is full and the peer is not reading —
whoever holds the lock is blocked in the syscall, holding up every other stream on the connection.
This is bounded by `Http2Limits.WRITE_TIMEOUT_MS` (30 s), enforced by a single shared daemon
thread (`Http2FrameWriter.WriteTimeoutReaper`) rather than `Socket#setSoTimeout`, which bounds
reads, not writes.

The reaper deliberately does **not** ask each write to record a `System.nanoTime()` deadline — an
early revision did, and this phase's own N=1 benchmark measured that single `nanoTime()` call
(plus the extra `volatile` field it required) costing enough to put per-write overhead over the
50 ns-over-baseline gate budget. Instead, the reaper scans every registered writer every
`SCAN_INTERVAL_MS` (50 ms) and counts *consecutive* scans a writer has been observed still blocked
(`writingThread` non-null); a writer blocked for more than `WRITE_TIMEOUT_MS / SCAN_INTERVAL_MS`
consecutive scans is interrupted. This trades a little precision — up to one scan interval of
slop, already inherent to any background-reaper design — for removing all per-write timing cost
from the path this document's gate criteria are strictest about.

## Benchmark methodology

`flash/src/jmh/java/dev/relism/flash/http2/frame/FrameWriterBenchmark.java` (a JMH source root
registered only under the `jmh` Maven profile — see `DECISIONS.md`, `DEC-17`, for why it does not
live in `src/test/java`) compares four harnesses at `threads` ∈ {1, 2, 4, 8, 16, 64}:

- `trylock_mpsc` — the shipped `Http2FrameWriter` design.
- `plain_lock` — every write blocks on `ReentrantLock.lock()` unconditionally (candidate (a)).
- `dedicated_thread` — every write hands off to one dedicated platform thread via the same
  `IntrusiveMpscQueue`, parked/unparked, never busy-polled (candidate (c)).
- `raw_unsynchronized` — no coordination at all; not a candidate (concurrent writers would tear
  each other's frames), included only to answer "what does a write cost with zero coordination",
  which the N=1 gate criterion is defined relative to.

Each JMH "operation" is a full burst: `threads` virtual producer threads each write 4 000 frames
of 512 bytes into a `CountingSink` that discards the bytes but atomically counts completed writes;
`runBurst` blocks until the count reaches the expected total, so the timed interval always covers
real completion, not mere submission (`write()` can return once an intent is merely *queued* on
the contended path — timing only "how long until every `write()` call returned" would flatter
whichever design most aggressively defers work). `@Threads` was not usable here: it requires a
compile-time constant, not a value swept via `@Param`, and JMH's own thread pool is platform
threads, not the virtual threads under test.

**Two honest caveats, stated plainly rather than glossed over (R3):**

1. **The sink is an in-memory counter, not a real socket.** "p999 latency ... on loopback" in the
   plan's gate wording implies real socket I/O; this harness measures writer-lock-contention
   latency in isolation from network variance, which is the right isolation for judging *this
   component*, but it means the recorded p999 numbers below are a lower bound on what a real
   loopback socket would show, not a direct stand-in for it. Frame size used is 512 B, not the
   plan's illustrative 1 KB — chosen to keep the burst's own array allocation small relative to
   JVM defaults; the writer's cost model does not depend on frame size (it copies nothing; see
   `WriteIntent`'s Javadoc), so this does not affect the gate conclusions.
2. **One JMH "op" is a whole burst (4 000 writes), not one write**, because `@OperationsPerInvocation`
   requires a compile-time constant and cannot vary with the `threads` `@Param`. Every burst also
   pays fixed harness costs common to *all four* designs equally: one `ExecutorService` (a
   virtual-thread-per-task executor) created and torn down, one `Future[]` array, one
   `long[threads][4000]` latency-sample array, and one fresh `BenchIntent` object allocated per
   write (matching the stress test's own pattern, not the writer's actual production contract —
   a real stream is long-lived and reuses itself as its own `WriteIntent`). Because this cost is
   identical across designs, **absolute** `gc.alloc.rate.norm` numbers below are dominated by this
   shared harness cost (~33 443 B/op), not by the design under test; the number that actually
   answers the "0 B/op" gate criterion is the **differential** between a design and the
   `raw_unsynchronized` baseline, which isolates exactly the bytes that design itself adds.

## Results

All runs: JDK 21.0.11 (Temurin), this development sandbox, JMH 1.37, `-Fork` per run noted below.
Raw JMH output is not reproduced in full here; the numbers below are the reported means with
their 99.9% CI half-widths.

### N=1 — throughput and allocation (`-f 4 -wi 5 -w 1s -i 12 -r 2s`, throughput; separately
`-f 2 -wi 3 -i 8`, `-prof gc`)

| design | ops/s (bursts/s) | derived ns/write | gc.alloc.rate.norm (B/op, per burst) |
|---|---|---|---|
| `trylock_mpsc` | 2007.930 ± 60.623 | 124.5 ns | 33 449.253 ± 13.383 |
| `raw_unsynchronized` | 3052.036 ± 52.515 | 81.9 ns | 33 443.349 ± 1.572 |

- **Overhead vs. raw unsynchronized:** 124.5 − 81.9 = **42.6 ns** (point estimate). Worst case
  within the 99.9% CI (slowest plausible `trylock_mpsc`, fastest plausible baseline):
  ≈ **47.9 ns**. Both are under the **50 ns** gate budget.
- **Allocation delta:** 33 449.253 − 33 443.349 = **5.9 B per 4 000-write burst** ≈ **0.0015 B per
  write** — within `trylock_mpsc`'s own ±13.383 error band, i.e. not distinguishable from zero.
  Consistent with the design: the fast path is `queue.hasWork()` (a volatile read) plus
  `ReentrantLock.tryLock()`/`unlock()` (well-known non-allocating on the JDK's implementation)
  plus one bulk `sink.write`. **Gate criterion: 0 B/op — PASS.**

### N=64 — throughput retention and tail latency (`-f 2 -wi 3 -w 1s -i 5 -r 1s`)

| design | N=1 writes/s (per-thread) | N=64 writes/s (aggregate) | retention | p999 @ N=64 |
|---|---|---|---|---|
| `trylock_mpsc` (shipped) | 8 721 148 | 5 712 640 | **65.5 %** | **11.8–14.2 µs** |
| `plain_lock` (candidate a) | 10 469 956 | 6 082 048 | 58.1 % | 1627–1952 µs |
| `dedicated_thread` (candidate c) | 2 673 964 | 5 718 528 | 213.8 %† | 1.5–6.7 µs |
| `raw_unsynchronized` (unsafe baseline) | 11 353 924 | 20 764 160 | n/a | n/a |

† `dedicated_thread`'s N=1 baseline is itself poor (every uncontended write still pays a full
park/unpark handoff to the dedicated thread — there is no fast path for the "only one writer"
case at all), so a >100% "retention" number reflects a bad denominator, not superlinear scaling.
It is reported for completeness, not as a pass/fail signal — the gate criterion is defined
relative to `trylock_mpsc`'s own N=1 baseline, which is the design that shipped.

- **`trylock_mpsc` throughput retention:** 65.5 % ≥ the required 60 %. **PASS.**
- **`trylock_mpsc` p999 latency:** 11.8–14.2 µs, far under the 1 ms budget. **PASS.**
- (Not gate-relevant, but part of why (b) was chosen over (a) and (c), per the plan's task 6:
  `plain_lock` blows past the 1 ms p999 budget by ~1000× under load — unfair blocking causes tail
  pile-up exactly as expected from a design with no fast path and no fairness guarantee.
  `dedicated_thread` has the best tail latency of the three but a **~3.3×** throughput penalty at
  N=1, because *every* write, even genuinely uncontended ones, pays a full thread handoff. Neither
  alternative is a better shipped default than `trylock_mpsc`.)

### Stress test — correctness under concurrency, 1000 iterations per N

Run via an ad hoc reflective driver invoking `Http2FrameWriterStressTest`'s private `runStress`
method directly (the shipped test class runs reduced counts for a fast default `mvn test`; this
is the full gate verification described in that class's own Javadoc), for `N` ∈ {1, 2, 8, 64,
256}, 1000 iterations each:

| Scheduler | n=1 | n=2 | n=8 | n=64 | n=256 | Total wall time |
|---|---|---|---|---|---|---|
| default parallelism | 0 failures | 0 failures | 0 failures | 0 failures | 0 failures | ≈ 9.1 s |
| `-Djdk.virtualThreadScheduler.parallelism=1` | 0 failures | 0 failures | 0 failures | 0 failures | 0 failures | ≈ 8.9 s |

Every byte of every frame arrived, correctly ordered per-producer, with no tearing, duplication,
or loss, in both configurations — **10 000 total stress runs, 0 failures.**

**Carrier pinning:** the `parallelism=1` run above was additionally run under
`-Djdk.tracePinnedThreads=full`, which prints a stack trace to stderr for any virtual thread found
blocked while pinning its carrier. Zero output — **no pinning observed**, consistent with the
design's exclusive use of `ReentrantLock` (never `synchronized`) on every path that can block.

## Gate criteria — final tally

| # | Criterion | Result | Verdict |
|---|---|---|---|
| 1 | N=1: 0 B/op | 0.0015 B/write differential vs. baseline, within noise | **PASS** |
| 1 | N=1: ≤50 ns overhead vs. raw unsynchronized | 42.6 ns point estimate, ≤47.9 ns worst-case CI | **PASS** |
| 2 | N=64: throughput ≥60% of N=1 per-thread rate | 65.5 % | **PASS** |
| 2 | N=64: p999 <1 ms (512 B frame, in-memory sink) | 11.8–14.2 µs | **PASS** |
| 3 | No carrier pinning under `-Djdk.tracePinnedThreads=full` | none observed | **PASS** |
| 4 | Stress test green at every N, 1000 iterations, incl. parallelism=1 | 10 000/10 000 | **PASS** |

**All four gate criteria are met. Verdict: GO.** `Http2FrameWriter` ships as designed —
`tryLock()` fast path, intrusive MPSC fallback — and Phase 4 may proceed. See `DECISIONS.md`,
`DEC-09`, for the decision-log entry recording this outcome alongside the plan's other decisions.

## What this design costs vs. what it saves

The honest framing (per R3, extended from HPACK's own to the writer): the writer's happy path
costs one uncontended CAS (`ReentrantLock.tryLock()`) plus a volatile read (`queue.hasWork()`)
plus the write syscall itself — on the order of tens of nanoseconds, measured above at ~42.6 ns
over a raw unsynchronized write. What it buys is the only thing that makes HTTP/2 multiplexing
possible on a codebase built around "one thread owns the socket": N concurrent streams can write
frames to the same connection without a naive per-frame lock (which the `plain_lock` comparison
above shows costs ~1000× more in tail latency once real contention appears), and without
committing every connection to a dedicated writer thread's per-write handoff cost (which the
`dedicated_thread` comparison shows costs ~3.3× throughput at the N=1 case that dominates real
traffic). Forty-two nanoseconds is a price worth paying once, on the one path that gates
multiplexed HTTP/2 correctness at all.
