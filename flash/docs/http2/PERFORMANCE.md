# HTTP/2 performance

## Method

Measurements were taken on 2026-08-13 under Linux 6.12/KVM with six exposed AMD Ryzen 7 1700X
cores, Temurin 21.0.11, JMH 1.37 and nghttp2 1.59.0. JMH component benchmarks use prepared,
reusable protocol state and forked JVMs. `h2load` exercises the real cleartext server on loopback;
Flash and nghttpd run on the same host in alternating order. Results are snapshots, not promises
for different hardware.

No "unmatched throughput" claim is supported. nghttpd is normally faster in this matrix; Flash's
numbers include framework routing, request-model assembly and handler dispatch that the static
reference server does not.

## Component results

The CI-controlled allocation and percentile numbers are in `BASELINES.md`. Additional average
time measurements from the same run were:

| Scenario | Result |
|---|---:|
| h2 responses across 1 live stream | 74.405 ns |
| h2 responses across 8 live streams | 705.368 ns |
| h2 responses across 64 live streams | 5,591.368 ns |
| h2 responses across 256 live streams | 28,961.681 ns |
| h2 POST lifecycle with 1 KiB DATA | 741.031 ns, 0.005 B/op |
| 1 MiB streaming response | 78,681.815 ns |

The multiplexing benchmark reports one complete response-encoding pass across all live streams,
not per-stream time. `Http2BodyBenchmark` separately covers the 1 KiB request-body shape and the
1 MiB response shape. `FrameWriterBenchmark` retains the Phase 3 contention matrix and its
per-write latency distribution.

## End-to-end h2load comparison

Each row uses at least 1,000 requests. Requested stream concurrency is capped first by Flash's
advertised 64-stream setting and then to 4,096 aggregate active streams so the 1,000-connection
rows remain bounded. Both requested and effective values are shown.

| Connections | Requested/effective streams | Flash req/s | nghttpd req/s |
|---:|---:|---:|---:|
| 1 | 1 / 1 | 2,136.18 | 12,786.42 |
| 1 | 10 / 10 | 18,396.56 | 83,521.26 |
| 1 | 100 / 64 | 17,039.55 | 66,746.76 |
| 10 | 1 / 1 | 11,247.08 | 37,838.66 |
| 10 | 10 / 10 | 25,055.12 | 104,964.84 |
| 10 | 100 / 64 | 3,878.28 | 67,303.81 |
| 100 | 1 / 1 | 5,517.94 | 42,319.09 |
| 100 | 10 / 10 | 1,818.52 | 26,732.25 |
| 100 | 100 / 40 | 7,042.85 | 136,585.90 |
| 1,000 | 1 / 1 | 1,393.17 | 3,877.62 |
| 1,000 | 10 / 4 | 10,374.83 | 40,976.05 |
| 1,000 | 100 / 4 | 49,622.10 | 85,344.40 |

The matrix found a correctness issue before it produced these final numbers: closed streams still
occupied live admission slots while their final write callback was pending. The bounded detach
fix is recorded as EX-57 and covered by regression tests.

## Tuning decisions

| Knob | Measurement | Decision |
|---|---|---|
| 16 KiB / 64 KiB / 1 MiB response frame | 1 MiB stream: 104,071 / 97,996 / 98,769 ns in the non-Huffman sweep | Keep 16 KiB. The roughly 6% gain at 64 KiB does not justify 4x per-connection buffer exposure on this noisy host. |
| 1 MiB initial receive window | 100 MiB Phase 11 transfer and the load matrix complete without flow stalls | Keep; it matches bounded receive capacity and changing it independently would not isolate a throughput claim. |
| half-window WINDOW_UPDATE hysteresis | 1 MiB streaming and 100 MiB transfer complete with steady pooled reads | Keep; no per-frame update traffic and no demonstrated reason to weaken backpressure. |
| 64 KiB inline body | 1 KiB inline materialization is one 1,040 B allocation; streaming steady state is ≈0 B/op | Keep the explicit one-array small-body tradeoff and stream larger bodies. |
| 64 × 16 KiB DATA buffers | 1 MiB streaming is 78,682 ns with ≈0 B/op; h2load stays bounded | Keep; larger chunks did not produce a clear win beyond the frame-size sweep. |
| `ScratchPool` bound | 64 objects per exposed CPU, capped at 4,096; full 1,000-connection matrix completes | Keep the capacity bound; it affects retained burst memory, not steady-state request instructions. |
| word-at-a-time route compare | 14.289 ns versus 22.313 ns bytewise, 36.0% faster | Keep. |
| SWAR header-end scan | 89.919 ns versus 128.460 ns scalar, 30.0% faster | Keep. |
| `SlicePool` size 4 | Header/path/query view benchmarks remain allocation-free | Keep; size changes lifetime capacity, not lookup work, and four simultaneous borrowed views match the documented contract. |
| runtime-value Huffman | representative response headers: 375.761 ns versus 180.129 ns at 16 KiB | Keep disabled by default; this header set is 109% slower to encode. |

The frame-size/Huffman factorial produced counterintuitive variation in the body-only rows, so it
was not used to claim a Huffman body effect: Huffman only prepares headers. This is treated as
host noise rather than reverse-engineered into a preferred result.

## Profiling

async-profiler 4.4 was run against the representative browser HPACK decode. The top CPU leaves
were `Huffman.decode` (72.67%), `HpackHeaderBlock.accept` (7.00%), `HpackDecoder.decode` (5.00%),
JVM byte-array copy (4.00%), `HpackHeaderBlock.copy` (4.00%), `HpackStaticTable.name` (2.00%),
`PooledSlice.reset` (1.00%), `PooledSlice.array` (0.67%), JVM byte-arraycopy (0.67%), and
`HpackDecoder.decodeString` (0.67%). Each belongs to decoding, bounded arena ownership, or the
copy that makes header lifetime independent of dynamic-table eviction; none is incidental
locking or logging.

The allocation profile produced no samples on the gated decode path. The realistic eight-writer
lock profile produced no sampled contended locks; the writer benchmark measured 185.605 bursts/s,
p50 1.2 µs, p99 5.3 µs and p999 41.6 µs. CPU, allocation and lock artifacts were generated under
`/tmp/phase17-*` and are intentionally not committed.

CI runs the complete suite with `-Djdk.tracePinnedThreads=full`. The forked allocation and p99
gates run only under the Maven `jmh` profile; the h2load comparison remains informational and
conditional because cross-runner throughput is not a stable correctness gate.

The reconstructed Phase-0 HTTP/1 comparison is documented in `BASELINES.md`. Its confidence
interval overlaps the Phase-17 result, while normalized allocation falls from 224.007 B/op to
0.007 B/op.
