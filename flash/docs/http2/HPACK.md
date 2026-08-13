# HPACK decoder

This document records the implementation constraints of Flash's RFC 7541 decoder. It is
contributor documentation, not application API documentation.

## Representation model

`HpackDecoder` accepts all RFC 7541 field representations: indexed fields, literals with
incremental indexing, literals without indexing, never-indexed literals, and dynamic-table size
updates. Prefix integers are bounded against overflow, Huffman padding and EOS are validated, and
decoded string lengths are checked while bytes are produced.

The static table is stored as 61 immutable name/value byte pairs. Encoder-oriented reverse lookup
uses fixed open-addressed integer tables built during class initialization; lookup never converts
header bytes to `String` and never calls `HashMap` on the hot path.

The dynamic table owns a bounded byte arena and a ring of primitive entry descriptors. Entry size
is `name length + value length + 32`, and eviction is oldest-first as required by RFC 7541 §4.1.
When the arena tail is too short, live entries are compacted into a contiguous prefix. This avoids
segmented views in every consumer and keeps indexed fields cheap to copy.

## Ownership and eviction safety

Views emitted by `HeaderSink` are callback-scoped. Production decoding targets a reusable
`HpackHeaderBlock` owned by the stream, which copies each name and value into its own arena.

The copy is required for correctness. Consider stream A referencing a dynamic-table entry while
its handler is running. The connection thread can then decode stream B, evict that entry, and
reuse its bytes. If stream A retained the dynamic-table view, its headers would silently change.
Per-stream storage removes that race without reference counting or synchronization.

The precise copy model is:

- HTTP/1.1 copies nothing per request but scans header bytes in the connection buffer.
- HTTP/2 copies decoded request headers into stream-owned storage because multiplexed handlers
  outlive subsequent HPACK mutations.
- Novel incrementally-indexed fields are also copied once into the connection's dynamic table.

`HpackEvictionRaceTest` contains both the unsafe borrowed-view demonstration and the stable
stream-owned result.

## Header-list rejection

The decoder counts RFC header-list size cumulatively. Once the configured limit is crossed it
stops emitting fields, but continues parsing the entire block and applying dynamic-table updates.
Only after the block ends does it throw `HeaderListSizeException`. The stream layer can reject the
request while the connection's compression state remains synchronized.

## CONTINUATION assembly

`ContinuationAssembler` copies HEADERS and CONTINUATION fragments into one bounded connection
buffer. It rejects interleaving, stream-id changes, excessive continuation count, and blocks that
exceed the configured capacity. `SegmentedByteView` is intentionally not used here: RFC 9113 §6.10
requires a contiguous, non-interleaved continuation sequence, and one bounded copy makes the HPACK
decoder and all downstream views simpler.

## Verification

- RFC 7541 Appendix C.1–C.6 vectors, including dynamic-table state after every sequence.
- Invalid integer, Huffman, index, size-update, and header-list inputs.
- Ten million deterministic random blocks; only typed protocol rejections may escape.
- JMH `-prof gc`: `decodeStaticRequest` measured 102.725 ns/op and 0.001 B/op on JDK 21.0.11. The
  latter is the profiler's sampling noise floor; no garbage collections occurred.

## Encoder and response path

The encoder is stateless and deliberately uses only the static table plus literal fields without
indexing. It emits a dynamic-table-size update of zero at the start of the connection's first
response block. This avoids mutable compression state shared by concurrent streams; the trade-off
is a few more wire bytes for repeated custom response fields.

Status and known content-type fields are HPACK-encoded during class initialization. The cached Date
header refreshes both its HTTP/1 and HPACK forms once per second. Runtime values are raw literals by
default; `FlashConfiguration.h2HuffmanDynamicValues` enables Huffman coding when deployment-specific
measurements justify its CPU/wire-size trade-off.

`Http2ResponseWriter` is reusable per stream. It lowercases field names, removes forbidden
connection-specific fields, enforces the peer's header-list bound, keeps HEADERS and CONTINUATION
frames in one write intent, and appends a small fixed DATA body when flow-control permits.

JMH `-prof gc` measured the representative response path at 174.309 ns/op and 0.001 B/op on JDK
21.0.11, with no garbage collections. The reported allocation is the profiler noise floor.
