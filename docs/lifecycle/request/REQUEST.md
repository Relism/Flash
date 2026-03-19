# Request

The `Request` object is the immutable view of an incoming HTTP/1.1 request passed to every
handler. It is constructed by `RequestParser`, path/query parameters are injected once by the
router, and then it is handed to your handler unchanged.

```java
server.get("/users/{id}", (req, res) -> { ... });
```

---

## Method and path

```java
HttpMethod method = req.method();  // GET, POST, PUT, …
String     path   = req.path();    // "/users/42" — decoded UTF-8, no query string
```

`path()` allocates a `String` by decoding the underlying `ByteView`. It is a convenience for
handlers that need the raw path; the router itself never calls it (it works on the `ByteView`
directly for zero-alloc routing).

---

## Headers

```java
String        ct      = req.header("Content-Type");   // first value, or null
List<String>  accepts = req.headers("Accept");         // all values in order
List<String>  all     = req.headers();                 // every header value
```

Lookup is **case-insensitive**: `"content-type"` and `"Content-Type"` resolve to the same header.
`header()` returns `null` if the header is absent. `headers()` returns an empty list.

---

## Path parameters

Path parameters are declared in the route pattern with `{name}` syntax and injected by the
router before the handler runs.

```java
server.get("/users/{id}/orders/{orderId}", (req, res) -> {
    String id      = req.param("id");
    String orderId = req.param("orderId");
});
```

`param()` returns `null` if the route has no such parameter or is not parametric at all.
It never throws.

---

## Query parameters

The query string is parsed lazily on the first `query()` or `queries()` call and cached for
the lifetime of the request.

```java
// GET /search?q=flash&page=2&tag=java&tag=jvm

String       q    = req.query("q");       // "flash"
String       page = req.query("page");    // "2"
List<String> tags = req.queries("tag");   // ["java", "jvm"]
String       miss = req.query("absent");  // null
```

`query()` returns the first value for duplicate keys. `queries()` returns all values in
declaration order. Both return `null` / empty list for absent parameters, never throw.

---

## Body

See [BODY.md](BODY.md) for a full reference. Quick summary:

```java
// Small payloads — materialise into byte[]
byte[] json = req.body().bytes();

// Large payloads — stream without heap allocation
InputStream in = req.body().stream();

// Check size before choosing
long size = req.body().contentLength(); // -1 if Transfer-Encoding: chunked
```

The two modes are **mutually exclusive per request**. Calling `stream()` after `bytes()` returns
a `ByteArrayInputStream` over the cached array; calling `bytes()` after `stream()` has been
partially read produces undefined results.

---

## Keep-alive and drain

After the response is fully written to the socket, the server calls `req.drain()` automatically
on keep-alive connections. This discards any unread body bytes so the socket is correctly
positioned for the next pipelined request. You never need to call this yourself.

**Important:** drain happens *after* the response write, not immediately after the handler
returns. This matters for streaming responses that consume the request body on a separate thread
(e.g. a pipe-fed echo): by the time drain runs, the body has already been consumed.
