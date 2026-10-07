# HTTP server metrics

`MicrometerModule` registers an `ExchangeInterceptor` that times every request and records it in
the registry.

```java
new MicrometerModule(registry).exclude("/metrics", "/healthz")
```

| Method | Meaning |
|---|---|
| `MicrometerModule(MeterRegistry)` | The registry to record into. Registered as a service |
| `exclude(String...)` | Exact route templates to leave out, for example an exporter endpoint |

If the registry is not `AutoCloseable`, the module closes it when the app stops.

## The timer

One timer named `http.server.requests`, with tags:

| Tag | Value |
|---|---|
| `method` | HTTP method |
| `uri` | The route template, such as `/users/{id}`, never the concrete path. Unmatched requests use `NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405) or `UNKNOWN` |
| `status` | The status code, or `UNKNOWN` outside 100 to 599 |
| `outcome` | `INFORMATIONAL`, `SUCCESS`, `REDIRECTION`, `CLIENT_ERROR`, `SERVER_ERROR` or `UNKNOWN` |

Because the tag is the template, the number of series is bounded by routes, methods and statuses.

## What is measured

The interceptor wraps the whole route, outside the error boundary, so the status is the one the
client receives, including error handler answers. The time covers the middleware, the handler, error handling and, for a
`Response.streaming` body, the callback that writes it. For a fixed body it ends before the response
is written to the socket.

Routes of duplex handlers (WebSocket, SSE, gRPC) are not recorded.

Timers are created on first use for each route, method and status and then reused, so recording
after the first request for a combination does no lookup by name.
