# HTTP/2 connection control

`Http2Connection` owns only connection-level protocol state. It verifies the preface, drives the
frame reader, dispatches control frames and performs shutdown. HPACK fragment extraction and decode
live in `Http2HeaderBlockDecoder`; socket serialization remains exclusively in
`Http2FrameWriter`. Stream dispatch and application handlers are separate layers.

Each accepted HTTP/2 socket receives a new `Http2Connection`. Sharing the stateless
`Http1Connection` implementation is safe, but sharing an HTTP/2 instance would leak dynamic HPACK,
SETTINGS, flow-control and GOAWAY state between peers.

## Demultiplexing invariant

The demux thread never invokes application work. It reads and validates frames, updates bounded
connection state, and enqueues or directly writes control frames. A registered handler cannot delay
SETTINGS or PING processing. The connection reader polls at a short interval so server shutdown is
observed promptly, while `Http2FrameReader` retains one non-renewable absolute deadline for a
partially received frame; polling therefore does not weaken slow-frame protection.

## Settings

| Identifier | Default | Validation and handling |
|---|---:|---|
| `HEADER_TABLE_SIZE` | 4096 | Unsigned 32-bit; locally capped |
| `ENABLE_PUSH` | 1 | Only 0 or 1; Flash advertises 0 |
| `MAX_CONCURRENT_STREAMS` | unlimited | Unsigned 32-bit |
| `INITIAL_WINDOW_SIZE` | 65535 | At most 2^31-1 |
| `MAX_FRAME_SIZE` | 16384 | 16384 through 16777215 |
| `MAX_HEADER_LIST_SIZE` | unlimited | Unsigned 32-bit |

Unknown identifiers are ignored. A payload is validated as a transaction before values are
committed. The initial-window delta is handed to the stream table as one operation: negative stream
windows are valid, but any result above 2^31-1 rejects the complete update with
`FLOW_CONTROL_ERROR`. Every non-ACK SETTINGS frame receives an empty ACK; locally sent settings are
bounded and have an acknowledgement deadline.

## Priority control writes

`Http2FrameWriter` has one priority MPSC lane in front of its ordinary stream-data lane. PING and
SETTINGS acknowledgements, RST_STREAM and GOAWAY use reusable control intents from the connection
scratch. They can overtake queued DATA but never split or interrupt a socket write already in
progress. Both PING and SETTINGS response queues are bounded.

## Shutdown

Graceful shutdown follows the two-stage protocol:

1. Send GOAWAY with last-stream-id 2^31-1 and `NO_ERROR`.
2. Send a connection PING and wait for its matching ACK, establishing a round trip.
3. Send a second GOAWAY with the real last processed stream id, then close after current work.

A connection error instead sends one GOAWAY with the precise error code, the real last processed
stream id and a bounded diagnostic string. A preface mismatch closes silently because the peer has
not established a valid HTTP/2 connection.

## Verification

The reusable control lifecycle (preface, SETTINGS/ACK, PING/PONG, WINDOW_UPDATE and received
GOAWAY) measures 974.263 ns/op and 0.008 B/op on JDK 21.0.11; the allocation figure is the JMH GC
profiler noise floor with no collections. `curl 8.5.0` using h2c prior knowledge completed the
handshake and observed both clean GOAWAY stages. It exits with code 56 because this phase
deliberately sends no response HEADERS or DATA; those arrive with the response and stream phases.

h2spec 2.6.0 passes 28 of the 35 selected section 3, 4, 6.5, 6.7, 6.8 and 6.9 cases, including all
connection-owned SETTINGS validation, PING, GOAWAY, frame-format, HPACK interleaving and
connection-window cases. Six failures require response HEADERS/DATA or per-stream flow control and
remain assigned to the response, stream and DATA phases. The seventh is h2spec's expectation of a
GOAWAY after an invalid preface; Flash intentionally closes without writing because no valid HTTP/2
connection exists yet, as permitted by RFC 7540 §3.5 and required by this implementation's preface
contract.
