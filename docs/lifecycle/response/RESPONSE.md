# Response

The `Response` object is the mutable view of an outgoing HTTP/1.1 response. It is
pre-initialised by the server (`200 / text/plain`) and passed to every handler as `res`.
All mutating methods return `this` for fluent chaining.

```java
server.get("/hello", (req, res) -> res.body("hello"));
server.post("/items", (req, res) -> res.status(HttpStatus.CREATED).body("created"));
```

---

## Status code

```java
res.status(404)                   // int overload — phrase looked up via HttpStatus.bytesForCode()
res.status(HttpStatus.NOT_FOUND)  // enum overload — pre-encoded bytes written directly, zero lookup
```

Prefer the `HttpStatus` overload on hot paths: the pre-encoded `byte[]` is written to the socket
without any lookup. The `int` overload falls back to an O(1) array lookup — still zero allocation,
just one extra indirection.

---

## Content type

```java
res.type(ContentType.JSON)           // enum — pre-encoded bytes, zero allocation
res.type("application/octet-stream") // String — encoded once at call time
```

Same pattern as `status`: the `ContentType` enum carries pre-encoded bytes; the `String` overload
encodes once and stores the result.

---

## Body modes

Three mutually exclusive modes. The last one set wins.

### Fixed body

```java
res.body("hello")           // String → UTF-8 bytes
res.body(new byte[]{...})   // raw bytes
```

Written with `Content-Length`. The byte array is stored as-is; no copy on the write path.

### Known-length stream

```java
res.stream(Files.newInputStream(path), Files.size(path))
```

Written with `Content-Length`. The stream is drained directly to the socket in the write path —
zero intermediate buffer beyond the OS socket buffer.

### Unknown-length stream (chunked)

```java
res.chunked(source)
```

Written with `Transfer-Encoding: chunked`. The server reads 8 KB at a time from `source`,
writes each chunk with its hex-encoded length, and closes with the terminal `0\r\n\r\n`.
No `Content-Length` header is emitted.

Use when the body length is not known upfront: generated content, pipe-fed streams, proxied
responses.

---

## Custom headers

```java
res.header("X-Request-Id", "abc123")
   .header("Cache-Control", "no-store")
```

Each header is encoded as `"Name: Value\r\n"` bytes **at call time** and stored in a list.
On the write path the pre-encoded arrays are written directly — zero allocation per request.

---

## Fluent chaining

All mutating methods return `this`. A complete response in one expression:

```java
return res.status(HttpStatus.CREATED)
          .type(ContentType.JSON)
          .header("X-Trace-Id", traceId)
          .body(json);
```

---

## Body mode decision guide

| Payload                          | Mode                          | Header emitted          |
|----------------------------------|-------------------------------|-------------------------|
| Small / fully known              | `body()`                      | `Content-Length`        |
| File or stream with known size   | `stream(is, length)`          | `Content-Length`        |
| Generated / piped / unknown size | `chunked(is)`                 | `Transfer-Encoding: chunked` |

---

## Memory model

| Call                        | Allocation                                      |
|-----------------------------|-------------------------------------------------|
| `status(HttpStatus)`        | none — writes pre-encoded `byte[]` from enum    |
| `status(int)`               | none — O(1) array lookup at write time          |
| `type(ContentType)`         | none — pre-encoded `byte[]` from enum           |
| `type(String)`              | encodes once at call time, stored as `byte[]`   |
| `body(String)`              | one UTF-8 encode at call time                   |
| `body(byte[])`              | none — array stored by reference                |
| `stream(is, len)`           | none — stream drained directly to socket        |
| `chunked(is)`               | 8 KB read buffer per write call (stack-local)   |
| `header(name, value)`       | one UTF-8 encode at call time per header        |
