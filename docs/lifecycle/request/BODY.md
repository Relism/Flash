# Request Body

`req.body()` returns a `RequestBody` — a lazy accessor that exposes the HTTP request body in
two mutually exclusive modes. Choose one per handler based on the payload size and how you
intend to consume it.

---

## Two modes

### `bytes()` — materialise

```java
byte[] body = req.body().bytes();
```

Reads the full body into a `byte[]` and caches the result. Safe to call multiple times — the
second call returns the same cached array, no I/O is performed again.

Use for:
- JSON parsing (`new String(body, UTF_8)` then parse)
- Small form data
- Any payload that must be inspected in full before responding

**Limit:** throws `IllegalStateException` if `Content-Length` exceeds `Integer.MAX_VALUE`
(~2 GB). For larger bodies use `stream()`.

**Chunked bodies:** reads until `ChunkedInputStream` signals EOF, then caches.

---

### `stream()` — zero-copy

```java
InputStream in = req.body().stream();
```

Returns a bounded `InputStream` without allocating the full body upfront.

Use for:
- Large file uploads
- Bodies passed directly to disk, a database, or another stream
- Multipart parsing (via `Multipart.of(req)` which calls this internally)

**Fixed-length bodies:** a `SequenceInputStream` of any pre-buffered header bytes (from the
`RequestParser` read buffer, already in memory) followed by a bounded view of the socket
stream. The small pre-buffered slice costs nothing extra.

**Chunked bodies:** returns the raw `ChunkedInputStream` directly. It de-chunks on the fly —
reads the hex chunk-size line, the data, and the trailing CRLF — and signals EOF at the end
of the last chunk, leaving the socket positioned correctly for the next keep-alive request.

**After `bytes()`:** if the body was already materialised, `stream()` returns a fresh
`ByteArrayInputStream` over the cached array. No I/O is performed.

---

## Mutual exclusivity

```java
// SAFE — pick one mode
byte[]      b = req.body().bytes();
InputStream s = req.body().stream();

// UNSAFE — bytes() then stream() is safe (stream() wraps the cache)
// UNSAFE — stream() partially read, then bytes() → undefined results
```

The rule: if you call `stream()` first and partially read it, calling `bytes()` afterwards
will either throw (if `contentLength` > 2 GB) or read only the remaining socket bytes into
the array, missing the already-consumed portion.

---

## Checking the size

```java
long size = req.body().contentLength();
```

- **> 0** — fixed-length body; exact byte count
- **== 0** — empty body (`Content-Length: 0` or no body present)
- **== -1** — `Transfer-Encoding: chunked`; size is unknown upfront

```java
boolean empty = req.body().isEmpty(); // true when contentLength == 0
```

---

## Decision guide

| Payload size       | Encoding  | Use            |
|--------------------|-----------|----------------|
| < 2 GB, fully needed | any     | `bytes()`      |
| Any size, pass-through | any   | `stream()`     |
| Multipart form     | any       | `Multipart.of(req)` (uses `stream()` internally) |
| Size unknown       | chunked   | `stream()`     |
