# Errors

An exception thrown by a handler, a middleware or the code that writes a deferred body goes to the
app's `ErrorHandler`. The result is written like a handler return value.

## HttpException

`HttpException` carries a status and message and has no stack trace, so refusing a request costs
what a return costs.

```java
throw HttpException.notFound("Project");                       // 404 {"error":"Project not found","status":404}
throw HttpException.unauthorized().header("WWW-Authenticate", "Bearer");
throw new HttpException(418, "short and stout");
```

| Factory | Status | Message |
|---|---|---|
| `badRequest(message)` | 400 | message |
| `unauthorized()` | 401 | `Unauthorized` |
| `forbidden()` / `forbidden(message)` | 403 | `Forbidden` / message |
| `notFound(what)` | 404 | `<what> not found` |
| `methodNotAllowed(allow)` | 405 | `Method Not Allowed`, with `Allow` |
| `conflict(message)` | 409 | message |
| `unprocessable(message)` | 422 | message |
| `tooManyRequests(seconds)` | 429 | `Too Many Requests`, with `Retry-After` |
| `internal(message)` | 500 | message |

`header(name, value)` adds a response header to the error answer.

## Default handler

| Failure | Answer |
|---|---|
| `HttpException` | Its status and headers, body `{"error":"<message>","status":<status>}` as `application/json`, message JSON-escaped |
| Any other exception | Logged at error level. `500` with `{"error":"Internal Server Error","status":500}` |
| No route (404) | `{"error":"Not Found","status":404}` |

In [development mode](config.md#development-mode) the unexpected-exception and 404 answers are HTML
pages, the first with the stack trace. In production nothing internal leaves the server.

## Custom handlers

```java
app.onError((failure, request, response) -> {
    if (failure instanceof NumberFormatException) return response.status(400).body("bad number");
    throw failure;       // anything else is answered with the default 500
});
app.onNotFound((req, res) -> res.status(404).body("no such page"));
```

`onError` replaces the default entirely, including the `HttpException` rendering, so a custom handler
should deal with it. The handler returns a value interpreted like a handler's return. If it throws,
Flash logs the failure and answers `500` with the default JSON body.

## Failures after the response started

Once a streaming or duplex response has committed its status and headers, no error page can be sent.
The exception is rethrown, and on HTTP/1.1 the connection is closed; on HTTP/2 the stream is reset.

## Malformed requests

A request that breaks the HTTP/1.1 syntax or a configured limit is rejected by the parser before
routing. It never reaches a handler or the error handler. See [http1.md](http1.md#limits-and-rejections).
