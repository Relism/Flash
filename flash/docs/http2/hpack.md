# HPACK

Flash implements RFC 7541 header compression with a different strategy for each direction.

## Decoding requests

One `HpackDecoder` per connection decodes every header block, because HPACK state is shared across
the streams of a connection. It supports the static table, the dynamic table, Huffman strings and
dynamic table size updates, all without allocating per header.

| Bound | Value | Failure |
|---|---|---|
| Dynamic table size | 4,096 bytes. A size update above it | `COMPRESSION_ERROR` |
| One string literal | 8,192 decoded bytes, checked while Huffman decoding | `COMPRESSION_ERROR` |
| Decoded header list | 32,768 bytes, counted as name + value + 32 for each field | Stream error `ENHANCE_YOUR_CALM` |
| `CONTINUATION` frames per block | 8 | `PROTOCOL_ERROR` |
| Time from `HEADERS` to `END_HEADERS` | 10 s | `ENHANCE_YOUR_CALM` |
| Invalid index, truncated string, invalid Huffman, table size update after a field | | `COMPRESSION_ERROR` |

A header block is decoded fully even when its stream is refused, so the decoder state stays in step
with the peer.

## Encoding responses

The encoder keeps no state. It uses the static table and literals without indexing, so streams share
nothing mutable and can write concurrently. On the first response of a connection Flash sends a
dynamic table size update of 0, which tells the peer's decoder not to expect entries.

- `:status` for common codes, and `content-type` for the media types in `ContentType`, are encoded
  once at class load.
- `date` is re-encoded once per second and shared.
- Header names are written in lowercase. `connection`, `keep-alive`, `proxy-connection`,
  `transfer-encoding` and `upgrade` are dropped from responses.
- Values built at runtime are written as plain bytes. `h2HuffmanDynamicValues(true)` applies Huffman
  coding to them, at the cost of an encoding pass.
- A response whose header list exceeds the peer's `SETTINGS_MAX_HEADER_LIST_SIZE` fails the stream.
