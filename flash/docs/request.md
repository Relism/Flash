# Request

`Request` is what a handler or middleware reads. It is the same type over HTTP/1.1 and HTTP/2.

```java
app.get("/users/{id}", (req, res) -> {
    long id = req.paramLong("id");                   // /users/42
    int page = req.queryInt("page", 1);              // ?page=2
    String accept = req.header("Accept");
    return users.find(id, page);
});
```

## Lifetime

One `Request` exists per connection (HTTP/1.1) or per stream (HTTP/2) and is repositioned over each
new request. Copy what you need. The `String` values it returns are independent copies and safe to
keep. `Request` itself, `ByteView`s from `headerView`, and the body streams are not.

With [`dev(true)`](config.md#development-mode), any call on a request after its handler returned
throws `IllegalStateException`, so a retained request fails at the offending call instead of
returning the next request's data.

## Request line

| Method | Returns |
|---|---|
| `method()` | `HttpMethod`: `GET POST PUT DELETE PATCH OPTIONS HEAD TRACE CONNECT PURGE QUERY`, or `null` for any other token, which only a route for every method receives |
| `path()` | The path without the query, bytes decoded as UTF-8. Percent escapes are not decoded |
| `rawQuery()` | The query string without `?` as a `ByteView`, or `null` |
| `requestLine()` | The parsed line: method, path, query, protocol, headers. `getMethodName()` is the method token as text, `getMethodToken()` its bytes |
| `connectProtocol()` | For an HTTP/2 extended CONNECT, the `:protocol` value (`websocket`), otherwise `null`. Such a request is routed as a `GET` |

## Headers

Names are case-insensitive.

| Method | Returns |
|---|---|
| `header(name)` | First value, or `null` |
| `headers(name)` | All values in order |
| `headers()` | Every header line's value, in order. For debugging |
| `headerView(name)` | The first value as a `ByteView` over the request's own bytes. No allocation. Valid while the handler runs |
| `cookie(name)` | Value of one cookie, or `null`. Read in place from the `Cookie` header. HTTP/2 clients may split cookies across several headers, and all are read |
| `origin()` | `scheme://Host`. Both parts are chosen by the client: do not use it to build security decisions |

Over HTTP/2, `:authority` is also visible as `Host`, so `header("Host")` works on both protocols.

## Path and query parameters

| Method | Behavior |
|---|---|
| `param(name)` | Path parameter from `{name}`, or `null`. `param("**")` is the rest of a path declared with a trailing `/**` |
| `paramLong(name)`, `paramInt(name)` | Parsed from the path bytes. Missing or not a number: `400` |
| `query(name)` | First value of the query parameter, or `null` |
| `queries(name)` | All values in order |
| `queryLong(name, fallback)`, `queryInt(name, fallback)` | Number, or `fallback` when absent or empty. Malformed: `400` |

Numbers accept an optional sign and at most 18 digits. `paramInt` and `queryInt` answer `400` when
the value does not fit an `int`. Query values are percent-decoded and `+` becomes a space. An invalid
`%` sequence is kept as written.

```
GET /search?tag=a&tag=b&q=hello+world
req.queries("tag")   -> ["a", "b"]
req.query("q")       -> "hello world"
```

## Body

`body()` returns a `RequestBody`. Use one read mode per request.

| Method | Behavior |
|---|---|
| `bytes()` | Reads the whole body into an array and caches it. Fails above 2 GB |
| `stream()` | An `InputStream` over the body, read straight from the connection. For a chunked body it is the de-chunking stream |
| `contentLength()` | Declared length, or `-1` for a chunked body |
| `isEmpty()` | `contentLength() == 0` |
| `fullyRead()` | Whether the body has been consumed |

Bodies are bounded by [`maxBodySize`](config.md#limits) (default 10 MiB):

| Case | Result |
|---|---|
| HTTP/1.1, `Content-Length` above the limit | `413` before any body byte is read. The connection is closed |
| HTTP/1.1, chunked body that passes the limit | `413` thrown from the read, handled by the error handler |
| HTTP/2, `content-length` or received data above the limit | The stream is reset with `PROTOCOL_ERROR` |

Whatever the handler leaves unread is discarded after it returns so the connection can serve the
next request. Over HTTP/1.1 this holds up to [`maxDiscardedBodySize`](config.md#limits), 2 MiB by
default: a larger `Content-Length`, or a chunked body, left
unread closes the connection instead (see [http1.md](http1.md#unread-bodies)), so a client is not kept
sending an upload the handler refused.

### Form fields

`form()` parses an `application/x-www-form-urlencoded` body into a `QueryParams` with `get(name)` and
`getAll(name)`. It reads the whole body.

### Multipart

`Multipart.of(request)` parses `multipart/form-data` from `body().stream()` without loading it:

```java
Multipart parts = Multipart.of(req);
String title = parts.field("title");            // text field, buffered
Part avatar = parts.file("avatar");             // streamed from the connection
Files.copy(avatar.stream(), target);
```

| Method | Behavior |
|---|---|
| `field(name)` | First text field with that name, or `null` |
| `file(name)` | First file part with that name, or `null`. Its stream must be consumed before the next scan call |
| `parts()`, `parts(name)` | Scan everything and buffer every part, files included |

The parser moves forward only. Text fields passed while looking for a file are buffered; file bodies
passed while looking for a field are discarded. A request that is not `multipart/*` or has no
`boundary` throws `IllegalArgumentException`. `Part` exposes `name()`, `filename()`,
`contentType()`, `isFile()`, `stream()`, `materialize()` and `text()`. A part with a `filename` is a
file. The class is not thread safe.

Limits on multipart input are in [http1.md](http1.md#limits-and-rejections).

## Trailers

`trailers()` returns the request's trailer fields as a `HeaderView`. Call it after the body is fully
read, otherwise it throws `IllegalStateException`. Both protocols supply them.

## Connection

| Method | Returns |
|---|---|
| `remoteAddress()` | The peer's `InetSocketAddress`, `null` for a hand-built request |
| `clientAddress()` | The client's IP as a string: see below |
| `isSecure()` | Whether the connection uses TLS |
| `sslSession()` | The `SSLSession` (peer certificates for mutual TLS, cipher), `null` over plain HTTP |

`clientAddress()` returns the peer address, except when the peer is listed in
[`trustedProxies`](config.md#limits). Then it reads `X-Forwarded-For` from the right and returns the
first entry that is not itself a trusted proxy, or the leftmost entry if all are. Without
`trustedProxies` the header is ignored.

## Tests

`new Request(RequestLine, byte[] body)` builds a request without a connection, for unit tests of
handlers.
