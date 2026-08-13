# The Frame Layer (Phase 5)

Audience: contributors. This is the design record for `dev.relism.flash.h2.frame`'s frame
reading, validation, and writing — the 9-byte header and payload boundary, with no connection
semantics, no streams, and no HPACK above it.

## Why this is simpler than the h1 parser

HTTP/1.1 request parsing must scan for `\r\n\r\n` (`RequestParser`, `ByteScan.indexOfCrLfCrLf`)
because nothing in the h1 wire format states the header block's length up front. HTTP/2 states
every frame's payload length in the first three bytes of its 9-byte header — nothing is ever
scanned for. `Http2FrameReader` is a length-prefixed reader and nothing more: read 9 bytes,
decode the length, ensure that many more bytes are available, done.

## The wire format

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

`R` (RFC 9113 §4.1) is reserved and MUST be ignored on receipt — `FrameHeader.reset` masks it
out of `streamId()` once, so no caller has to remember to.

## Package layout

```
dev.relism.flash.h2.frame
├── FrameType            the 10 known types + per-type validation descriptor (min/max length, stream-id rule)
├── FrameFlags            END_STREAM/ACK/END_HEADERS/PADDED/PRIORITY bit constants + predicates
├── FrameHeader           flyweight over a read buffer: length/type/flags/streamId/payloadOffset
├── Http2FrameReader      length-prefixed reader, RequestParser's buffer/compaction discipline
├── FrameValidator        table-driven per-type RFC validation, specific error code per rule
├── Padding               RFC 9113 §6.1/§6.2 pad-length byte + trailing padding, DATA/HEADERS
├── FrameWriteBuffer      beginFrame()/endFrame() length back-patching over a ByteWriter
├── Http2FrameWriter      (Phase 3) the connection's single serialized writer — unchanged here
├── WriteIntent           (Phase 3) unchanged
└── IntrusiveMpscQueue    (Phase 3) unchanged
```

## The validation table

Every rule below is enforced by `FrameValidator.validate(FrameHeader, insideHeaderBlock)`, in
this order: unknown-type handling, `SETTINGS`' modulus-6 special case, the generic
min/max length bounds, the `MAX_FRAME_SIZE_LOCAL` ceiling, the stream-id rule, then
`PUSH_PROMISE`'s always-reject rule.

| Type | Code | Length | Stream id | Notes / RFC |
|---|---|---|---|---|
| DATA | 0x0 | 0..MAX_FRAME_SIZE | required (≠0) | §6.1. Padding via `Padding.unpad`. |
| HEADERS | 0x1 | 0..MAX_FRAME_SIZE | required (≠0) | §6.2. Padding + PRIORITY fields (Phase 7+ parses the latter). |
| PRIORITY | 0x2 | exactly 5 | required (≠0) | §6.3. Deprecated (§5.3.2) — parsed, discarded, never acted on. |
| RST_STREAM | 0x3 | exactly 4 | required (≠0) | §6.4. The 4 bytes are the error code. |
| SETTINGS | 0x4 | multiple of 6 | forbidden (=0) | §6.5. Modulus checked before the generic bounds. |
| PUSH_PROMISE | 0x5 | ≥4 | required (≠0) | §6.6. Always `PROTOCOL_ERROR` from a client — never sent by Flash. |
| PING | 0x6 | exactly 8 | forbidden (=0) | §6.7. Opaque 8-byte payload, echoed on ACK. |
| GOAWAY | 0x7 | ≥8 | forbidden (=0) | §6.8. Last-stream-id (4) + error code (4) + optional debug data. |
| WINDOW_UPDATE | 0x8 | exactly 4 | either | §6.9. 0 = connection window, ≠0 = one stream's window. |
| CONTINUATION | 0x9 | 0..MAX_FRAME_SIZE | required (≠0) | §6.10. Continues a header block; see the flood guard below. |
| *(unrecognised)* | >0x9 | — | — | §4.1: ignored outside a header block, `PROTOCOL_ERROR` inside one (§6.10). |

**The error code is not uniform per type** — a `SETTINGS` frame with a bad length is
`FRAME_SIZE_ERROR`; the same frame with a non-zero stream id is `PROTOCOL_ERROR`. Every violation
in the table above carries its own RFC citation and the specific code that citation mandates;
`FrameValidatorTest` has one test per row asserting the exact code, not merely "an exception".

## Ignore vs. reject policy

RFC 9113 §4.1 makes unknown frame types part of the protocol's extension mechanism: an endpoint
that does not recognise a type MUST read and discard its payload, never reject the connection for
it. `FrameType.fromCode` returns `null` for anything above `CONTINUATION` (0x9); `FrameHeader`
still exposes the raw `typeCode()` for logging even when `type()` is `null`.

The one exception (§6.10): if an unrecognised-type frame arrives **between** a HEADERS/
PUSH_PROMISE frame that lacked `END_HEADERS` and the CONTINUATION that eventually sets it, the
HPACK decoder's state has nowhere to put that frame's bytes without desynchronizing — so this one
case *is* a `PROTOCOL_ERROR`, tracked by `FrameValidator.validate`'s `insideHeaderBlock`
parameter (owned and threaded through by the Phase 8 connection loop, which is the only caller
that knows whether a header block is currently open).

`PRIORITY` frames are a different kind of "ignore": they are a recognised, well-formed type that
Flash chooses not to act on (RFC 9113 §5.3.2 deprecates priority signalling and permits an
implementation to disregard it) — they are still fully parsed and validated like any other frame,
just never influence scheduling. `PUSH_PROMISE` is the opposite: recognised, but **always**
rejected when received (Flash advertises `SETTINGS_ENABLE_PUSH=0` and never sends one itself), so
receiving one at all can only mean the peer has the client/server roles backwards.

## Buffer discipline and the frame-size defence

`Http2FrameReader` never grows its buffer to accommodate a declared length before checking that
length against `Http2Limits.MAX_FRAME_SIZE_LOCAL` — the check happens first, so a hostile 16 MB
declared length is rejected at the cost of reading 9 bytes, not at the cost of a 16 MB
allocation. This mirrors `RequestParser`'s own `EX-08` discipline (bound the request line before
trusting it) applied to the frame layer's own attack surface.

The buffer itself follows `RequestParser`'s compact-before-grow policy: unconsumed bytes slide to
offset 0 when there is room to do so without growing, and growth only happens when compaction
alone cannot make room — bounded, because the reader's own length check already rejected
anything that would require growing past `9 + MAX_FRAME_SIZE_LOCAL`.

## Padding

`Padding.unpad` locates the actual data range within a `PADDED` frame's payload: 1 byte of
pad-length, then data, then that many padding bytes (whose contents carry no meaning — they exist
only to obscure payload size from network observers). A pad length greater than or equal to the
whole payload length is `PROTOCOL_ERROR` (RFC 9113 §6.1), checked before any arithmetic that
could otherwise underflow. Flow-control accounting for padded DATA frames (RFC 9113 §6.9.1: the
*whole* payload counts against the window, not just the data) is Phase 11 scope — `Padding` only
locates the data range, it performs no window bookkeeping itself.

## Writing: `FrameWriteBuffer`'s back-patching

A frame's length is rarely known before its payload is serialized (an HPACK-encoded header block,
in particular, has no cheap way to be measured in advance). `FrameWriteBuffer.beginFrame` writes
a 9-byte header with a placeholder length; the caller writes the payload directly through the
same `ByteWriter`; `endFrame` computes the actual length from how far the writer has advanced and
rewrites the three length bytes in place. This is *why* `Http2FrameWriter` (Phase 3) serializes a
complete buffer before ever taking the connection lock, rather than streaming bytes as they are
produced — streaming would need the length upfront, which back-patching deliberately avoids
needing.

## `EX-37`, found while building this phase's tests

`BufferedByteSource`'s deadline mechanism (`EX-07`'s actual fix) turned out to have zero dedicated
unit tests and an unconditional `socket.setSoTimeout(...)` call that NPE'd against the `null`
socket every isolated unit test in this codebase uses. Found while writing
`Http2FrameReaderTest`, fixed, and given its own regression suite (`BufferedByteSourceTest`) —
full writeup in the plan's registry, `EX-37`.

## Testing

- `Http2FrameReaderTest` — round-trips every frame type, boundary lengths (0, 1, 16383, 16384,
  16385), a frame split across three socket reads, a frame exactly filling the initial buffer,
  multiple sequential frames, clean-EOF-vs-mid-frame-EOF, and reserved-bit masking.
- `FrameValidatorTest` — one test per RFC-mandated rejection above, asserting the specific
  `Http2ErrorCode`.
- `Http2FrameReaderFuzzTest` — 10 000 000 random-length (0–64 byte), random-content inputs; only
  `Http2Exception`, `EOFException`, or `SocketTimeoutException` may escape. Green, ~14s.
- `PaddingTest` — every boundary of the pad-length arithmetic, including the exact
  `padLength == payloadLength - 1` (maximum valid) and `padLength >= payloadLength` (rejected)
  cases.
