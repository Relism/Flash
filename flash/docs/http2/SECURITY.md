# HTTP/2 security controls

HTTP/2 multiplexing lets one connection create disproportionate parser, stream and response work.
Flash therefore combines structural bounds, flow-control bounds and rate bounds. Rate counters use
two fixed half-window buckets, allocate nothing per frame and need no timer thread.
JMH on JDK 21 measures one rate-counter increment at 38.083 ns/op and approximately
`10^-4 B/op` (allocation noise floor, no GC).

| Limit | Default | Defence / tuning guidance |
|---|---:|---|
| `MAX_CONCURRENT_STREAMS` | 64 | Bounds simultaneously retained stream state. |
| `MAX_STREAMS_CREATED_PER_INTERVAL` | 400 / 10 s | Companion to Rapid Reset; tune with `h2MaxStreamsCreatedPerInterval`. |
| `MAX_RESET_STREAMS_PER_INTERVAL` | 200 / 10 s | CVE-2023-44487 Rapid Reset; tune with `h2MaxResetStreamsPerInterval`. |
| `MAX_CONTINUATION_FRAMES_PER_BLOCK` | 8 | CVE-2024-27316 CONTINUATION flood. |
| `MAX_HEADER_LIST_SIZE` | 32 KiB | Stops HPACK expansion before fields reach stream storage. |
| `MAX_HPACK_STRING_LENGTH` | 8 KiB | Bounds one decoded literal, including Huffman expansion. |
| `MAX_SETTINGS_PER_INTERVAL` | 100 / 10 s | Bounds mandatory SETTINGS acknowledgements. |
| `MAX_PINGS_PER_INTERVAL` | 200 / 10 s | Bounds mandatory PING acknowledgements. |
| `MAX_USELESS_FRAMES_PER_INTERVAL` | 10,000 / 10 s | Aggregate CPU bound for PRIORITY, WINDOW_UPDATE, empty DATA and unknown frames. |
| `MAX_SETTINGS_ACK_QUEUE_DEPTH` | 64 | Bounds queued SETTINGS control writes. |
| `MAX_PING_QUEUE_DEPTH` | 64 | Bounds queued PING control writes. |
| `MAX_EMPTY_DATA_FRAMES_PER_STREAM` | 1,000 | Stops DATA work that spends no flow-control credit. |
| `INITIAL_WINDOW_SIZE_LOCAL` | 1 MiB | Matches the bounded DATA pool; consumption, not receipt, returns credit. |
| `ServerConfig.maxBodySize` | 10 MiB | Per-stream request body bound, shared with HTTP/1.1. |
| `HEADER_BLOCK_ASSEMBLY_TIMEOUT_MS` | 10 s | Absolute HEADERS-to-END_HEADERS deadline. |
| `STREAM_IDLE_TIMEOUT_MS` | 60 s | Cancels retained inactive streams; tune with `h2StreamIdleTimeoutMs`. |
| `FRAME_READ_TIMEOUT_MS` | 20 s | Absolute partial-frame deadline. |
| `WRITE_TIMEOUT_MS` | 30 s | Interrupts a socket writer blocked by a peer that stopped reading. |
| `MAX_STREAMS_PER_CONNECTION` | 100,000 | Optional connection churn budget; zero disables, tune with `h2MaxStreamsPerConnection`. |
| `MAX_BYTES_PER_CONNECTION` | disabled | Optional wire-byte budget; tune with `h2MaxBytesPerConnection`. |
| `MAX_CONNECTION_LIFETIME_MS` | disabled | Optional lifetime rotation; tune with `h2MaxConnectionLifetimeMs`. |

`h2AbuseRateIntervalMs` changes the rolling interval for reset and stream-creation operator
limits. Breaching a connection-wide rate or budget produces GOAWAY `ENHANCE_YOUR_CALM`; malformed
stream-local messages use the RFC-defined stream error. The ordinary write queue is bounded by the
64 live streams and their single-in-flight response intent; control writes use the fixed scratch
slots above, so a slow reader cannot create an unbounded application queue.

The security suite covers Rapid Reset, stream churn, SETTINGS/PING/non-progress floods, 100,000
CONTINUATION frames, HPACK expansion, malformed names/pseudo-fields, configurable resource budgets,
header assembly deadlines and idle-stream cancellation. HTTP/1 request/header/body security tests
remain in the full suite and the shared public message model uses the same bounds on both paths.
