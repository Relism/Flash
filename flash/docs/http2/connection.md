# Connection

`Http2Connection` handles one connection. It reads and validates frames, keeps connection-level
state, and starts stream dispatch. It never runs application code: handlers run on separate virtual
threads, so a slow handler cannot delay `SETTINGS` or `PING` processing.

## Start

1. Read the 24-byte client preface under `FRAME_READ_TIMEOUT_MS`. A mismatch is answered with
   `GOAWAY(PROTOCOL_ERROR)`. A connection that closes before 24 bytes arrive ends silently.
2. Send the server `SETTINGS` and the connection `WINDOW_UPDATE` (see [README](README.md#what-is-advertised)).
3. The first frame from the client must be `SETTINGS`, and not an empty ACK, otherwise
   `PROTOCOL_ERROR`.

## Frame loop

Each iteration checks the connection budgets, closes idle streams, starts shutdown if the server is
stopping, and reads one frame. The read polls every 100 ms at most, so shutdown is noticed promptly. A
partial frame has one absolute deadline (`FRAME_READ_TIMEOUT_MS`, 20 s) that polling does not renew.

A frame is validated by type before it is handled:

- Lengths are checked against bounds per type, and above 16,384 is `FRAME_SIZE_ERROR`. `SETTINGS`
  length must be a multiple of 6.
- Stream id rules: `DATA`, `HEADERS`, `PRIORITY`, `RST_STREAM` and `CONTINUATION` need a stream;
  `SETTINGS`, `PING` and `GOAWAY` need stream 0; `WINDOW_UPDATE` accepts either. Violations are
  `PROTOCOL_ERROR`.
- Unknown frame types are ignored and counted as non-progress frames, except inside a header block,
  where any frame other than `CONTINUATION` is `PROTOCOL_ERROR`.
- A frame type other than `CONTINUATION` while a header block is open is `PROTOCOL_ERROR`.

An error is either a **stream error**, answered with `RST_STREAM` while the connection continues, or
a **connection error**, answered with `GOAWAY` carrying the last processed stream id, the error code
and a short debug string (at most 128 bytes), after which the connection ends.

## Control frames

| Frame | Handling |
|---|---|
| `SETTINGS` | Validated as a whole before any value is applied, then ACKed. Unknown ids are ignored. An `INITIAL_WINDOW_SIZE` change adjusts every open stream's send window at once; a result above 2^31-1 is `FLOW_CONTROL_ERROR`. `HEADER_TABLE_SIZE` is capped at 4,096 |
| `SETTINGS` ACK | Accounts for a settings frame Flash sent. An ACK with none outstanding is `PROTOCOL_ERROR`. No ACK within 10 s is `SETTINGS_TIMEOUT` |
| `PING` | Answered with a `PING` ACK carrying the same 8 bytes |
| `WINDOW_UPDATE` | Increases the connection or stream send window. A zero increment is a connection error on stream 0 and a stream error otherwise |
| `RST_STREAM` | Cancels the stream. Counts toward the reset rate limit |
| `PRIORITY` | Validated (a stream may not depend on itself) and otherwise ignored |
| `GOAWAY` | Records the peer's last stream id and error code, then the connection ends |
| `HEADERS`, `CONTINUATION` | See [streams.md](streams.md) and [hpack.md](hpack.md) |
| `DATA` | See [flow-control.md](flow-control.md) |

Control frames go through a priority lane in the [frame writer](response-writer.md#frame-writer), so
they can overtake queued response data but never interrupt a write already in progress.

## Shutdown

When the server stops, each HTTP/2 connection performs the two-step shutdown of RFC 9113 section
6.8:

1. Send `GOAWAY` with last stream id 2^31-1 and `NO_ERROR`, followed by a `PING`.
2. When that `PING` is acknowledged, send a second `GOAWAY` with the real last processed stream id,
   and end the connection.

Streams already accepted continue until the drain timeout (`shutdownDrainTimeoutMs`) closes the
socket.
