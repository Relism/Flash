# Design constraints

Flash keeps the request path free of per-request allocation where it can. These are the mechanisms
that do it, and what they mean for application code.

| Mechanism | Effect on your code |
|---|---|
| One `Request`, `RequestBody` and `Response` per connection (HTTP/1.1) or stream (HTTP/2), repositioned for each request | Do not keep them after the handler returns. See [request.md](request.md#lifetime) |
| Headers, path and query stay as bytes in the connection's buffer | `header`, `query` and `param` allocate only the `String` they return. `headerView` returns a view and allocates nothing |
| The router is a state machine over method and path bytes, built at boot | Declaring a route costs nothing at request time. See [routing.md](routing.md) |
| Middleware chains, services and handlers are composed once at boot | Registration order is read when the server starts, not when you call `use` |
| Status lines, media types and the `Date` header are pre-encoded | Use `HttpStatus`, `ContentType` and `PreEncodedHeader` for values that repeat |
| Connection scratch buffers come from a bounded pool | Memory per connection is bounded; see [transport.md](transport.md#per-connection-setup) |
| One virtual thread per connection, and per HTTP/2 stream | Blocking calls in a handler are fine |

## Handlers run concurrently

One handler instance serves many requests at once. State that changes belongs in services that are
thread safe, not in handler fields.

## Dev mode checks

`ServerConfig.dev(true)` makes use of a recycled `Request` or `Response` throw, which turns the
silent bug of keeping one into a stack trace. See [config.md](config.md#development-mode).
