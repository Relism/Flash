# Limits

HTTP/2 bounds every value that comes from the peer. The constants are in `Http2Limits`. Those marked
configurable are `ServerConfig` options; the rest are fixed in code.

## Capacity

| Limit | Value | Notes |
|---|---|---|
| Concurrent streams | 64 | Advertised. Excess is `REFUSED_STREAM` |
| Frame payload | 16,384 | Larger is `FRAME_SIZE_ERROR` |
| Header list | 32,768 | Advertised |
| `CONTINUATION` frames per block | 8 | Protects against CONTINUATION flood (CVE-2024-27316). Exceeding it is `PROTOCOL_ERROR` |
| Encoded header block, all fragments together | 32,768 | `COMPRESSION_ERROR` |
| `SETTINGS` entries per frame | 64 | `ENHANCE_YOUR_CALM` |
| HPACK string literal | 8,192 | |
| Consecutive empty `DATA` frames per stream | 1,000 | Stream reset, `ENHANCE_YOUR_CALM` |
| Inline request body | 64 KiB | |
| Request body buffers | 64 of 16 KiB | |
| Stream and connection receive window | 1 MiB | |
| HPACK dynamic table | 4,096 | |

## Rates

Counted in a rolling window of `h2AbuseRateIntervalMs` (10 s). Exceeding any of them ends the
connection with `GOAWAY(ENHANCE_YOUR_CALM)`.

| Counter | Default | Option |
|---|---|---|
| `RST_STREAM` received | 200 | `h2MaxResetStreamsPerInterval` |
| Streams created | 100,000 | `h2MaxStreamsCreatedPerInterval` |
| `SETTINGS` received | 100 | |
| `PING` received | 200 | |
| Non-progress frames: `PRIORITY`, `WINDOW_UPDATE`, empty `DATA`, unknown types | 10,000 | |

The reset counter targets Rapid Reset (CVE-2023-44487). The creation counter covers a client that
opens streams fast enough to stay under the reset limit.

## Connection budgets

| Budget | Default | Option |
|---|---|---|
| Streams per connection | 100,000 | `h2MaxStreamsPerConnection`, `0` disables |
| Bytes read per connection | 0 (off) | `h2MaxBytesPerConnection` |
| Connection lifetime | 0 (off) | `h2MaxConnectionLifetimeMs` |

Exceeding a budget is `GOAWAY(ENHANCE_YOUR_CALM)`.

## Timeouts

| Timeout | Value | Result |
|---|---|---|
| Header block assembly | 10 s | `ENHANCE_YOUR_CALM` |
| One frame, header to payload | 20 s | Connection closes |
| `SETTINGS` ACK | 10 s | `SETTINGS_TIMEOUT` |
| Socket write | 30 s | Connection fails |
| Stream idle | 60 s, `h2StreamIdleTimeoutMs` | `RST_STREAM(CANCEL)` |

## Reading a failure

A `GOAWAY` carries the error code and a debug string naming the cause, for example `RST_STREAM rate
exceeded` or `connection byte budget exceeded`. Flash logs unexpected failures of the frame loop, and
answers with `INTERNAL_ERROR`. A client that sees `REFUSED_STREAM` hit the 64 stream limit.
`ENHANCE_YOUR_CALM` means one of the rate, size or timeout bounds above.
