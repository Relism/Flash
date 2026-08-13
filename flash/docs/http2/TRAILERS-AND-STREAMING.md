# Trailers and streaming

Flash exposes the same request and response model on HTTP/1.1 and HTTP/2. Request trailers are
available through `Request.trailers()` after the body has reached EOF. Calling it earlier throws
`IllegalStateException`; this prevents handlers from observing an incomplete trailer section.
HTTP/1.1 reads trailers from the final chunk, while HTTP/2 decodes the trailing HEADERS block in
the connection's existing HPACK context.

Response trailers are added with `Response.trailer(name, value)` or a `PreEncodedHeader`. HTTP/1.1
uses chunked framing and writes the fields after the zero chunk. HTTP/2 writes a trailing HEADERS
block with `END_STREAM`; the final DATA frame deliberately does not carry `END_STREAM`.

`Response.streaming(producer)` is the push alternative to `stream(InputStream, length)` and
`chunked(InputStream)`. Its `ResponseStream` is a bounded blocking bridge. A producer runs on a
virtual thread and blocks when the protocol writer or the HTTP/2 flow-control windows cannot make
progress. This keeps backpressure explicit without callbacks or reactive types:

```java
return response.type("application/grpc").streaming(stream -> {
  try {
    for (byte[] message : messages) stream.write(message, 0, message.length);
    stream.trailer("grpc-status", "0");
  } catch (IOException failure) {
    throw new UncheckedIOException(failure);
  }
});
```

The transport supports the primitives required by gRPC, but the core does not provide protobuf
codecs, generated stubs, service descriptors, or a gRPC service API. Those belong in a future
`flash-ext-grpc` module. `GrpcInteropTest` verifies the boundary with the external `grpcurl` client
and a hand-written wire-format handler.

CONNECT requests follow RFC 9113 request pseudo-header rules: `:authority` is required and
`:scheme`/`:path` are forbidden. Their DATA remains subject to the ordinary request limits,
timeouts and two-level flow control.
