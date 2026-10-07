# Response

`Response` is the plan for what is sent: status, headers, body and trailers. Handlers and middleware
change it; the connection writes it after the whole chain has returned. Every mutator returns the
`Response`.

```java
return res.status(201)
          .type(ContentType.JSON)
          .header("Location", "/users/42")
          .body("{\"id\":42}");
```

A new response starts as `200` with `Content-Type: text/plain` and no body.

## Lifetime

Like `Request`, the response handed to a handler is reused for the next request on that connection or
stream. A handler may also return its own `new Response(...)`, which is an ordinary object. In
[development mode](config.md#development-mode) using the pooled response after the handler returned
throws.

## Status

| Call | Notes |
|---|---|
| `status(int)` | The reason phrase comes from `HttpStatus`. An unknown code is written as `<code> Unknown` on HTTP/1.1 |
| `status(HttpStatus)` | Pre-encoded status line |
| `redirect(url)` | `302` with `Location`, clears any body |
| `redirect(HttpStatus, url)` | The same with an explicit status, for example `PERMANENT_REDIRECT` |

`HttpStatus` lists the codes Flash knows by name. Headers cannot be changed once the response is
committed.

## Headers

| Call | Notes |
|---|---|
| `header(name, value)` | Adds a header. Names and values are written as ASCII |
| `header(name, long)` | A decimal value without creating a string |
| `header(PreEncodedHeader)` | A header encoded once, for example a `static final` constant |
| `type(ContentType)` | Pre-encoded media type. `ContentType.NONE` removes the `Content-Type` line |
| `type(String)`, `type(byte[])` | Any media type. A `byte[]` is kept, not copied |

`header` adds; it does not replace. Calling it twice with the same name sends two fields. A response
accepts at most 1,000 header calls and 65,536 bytes of header names and values; beyond that
`IllegalStateException` is thrown, which catches an unbounded loop.

Flash adds `Date` unless [`sendDate(false)`](config.md#protocol), and on HTTP/1.1 also
`Content-Length` or `Transfer-Encoding` and `Connection`.

```java
private static final PreEncodedHeader NO_STORE = new PreEncodedHeader("Cache-Control", "no-store");
...
res.header(NO_STORE);
```

## Body

| Call | Sent as |
|---|---|
| `body(byte[])`, `body(String)` | Fixed body with `Content-Length`. Strings are UTF-8 |
| `stream(InputStream, length)` | Known length, `Content-Length`. The stream is closed afterwards |
| `chunked(InputStream)` | Unknown length: chunked on HTTP/1.1, plain DATA frames on HTTP/2 |
| `streaming(Streaming)` | Written by your code after the middleware chain: see [streaming.md](streaming.md) |

Each of these replaces the others. For `204`, `304` and `1xx` no `Content-Length` and no body are
written. For `HEAD` no body is written; on HTTP/1.1 `Content-Length` is the one the `GET` would send.

## Trailers

`trailer(name, value)` and `trailer(PreEncodedHeader)` add fields after the body. Over HTTP/1.1 a
response with trailers is sent chunked. Names that are forbidden in trailers throw
`IllegalArgumentException`: pseudo-headers (`:name`), `content-length`, `transfer-encoding`,
`connection`, `host`, `te` and `trailer`, and any name or value containing a line break.

## Content types

`ContentType` holds the media types Flash encodes ahead of time: text (`TEXT_PLAIN`, `TEXT_HTML`,
`TEXT_CSS`, `TEXT_JAVASCRIPT`, `TEXT_XML`, `TEXT_CSV`, `TEXT_MARKDOWN`, `TEXT_EVENT_STREAM`),
application (`JSON`, `XML`, `BINARY`, `PDF`, `ZIP`, `GZIP`, `FORM_URLENCODED`, `MULTIPART_FORM`,
`GRAPHQL`, `NDJSON`, `PROTOBUF`, `MSGPACK`, `CBOR`, `LD_JSON`), images, fonts, audio and video.
`ContentType.ofExtension("png")` returns the media type for a file extension, with
`; charset=utf-8` on text types and `application/octet-stream` for an unknown one.

## Wire details

On HTTP/1.1 the head is built in one reused buffer. A body of up to 8,192 bytes is copied into it and
leaves in one write; a larger body follows in a second write. `Connection: keep-alive` or
`Connection: close` is written according to the request and to whether the server is stopping.
See [http1.md](http1.md).
