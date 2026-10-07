# Middleware, interceptors and CORS

## Middleware

```java
@FunctionalInterface
public interface Middleware {
    void handle(Exchange exchange, Next next) throws Exception;
}
```

A middleware runs around the handler's preparation. Call `next.handle(exchange)` to continue, or
return without calling it to answer yourself. Throw an `HttpException` to refuse.

```java
Middleware auth = (exchange, next) -> {
    if (exchange.request().header("Authorization") == null) throw HttpException.unauthorized();
    next.handle(exchange);
};

Middleware timing = (exchange, next) -> {
    long start = System.nanoTime();
    next.handle(exchange);
    exchange.response().header("X-Time-Us", (System.nanoTime() - start) / 1_000);
};
```

`Exchange` gives `request()` and `response()`. After `next` returns the handler's response is
prepared but not sent, so headers and status can still change. Once a streaming response or duplex
protocol has written its first bytes, `Response` is committed and further changes throw
`IllegalStateException`.

### Where middleware attaches

| Call | Applies to |
|---|---|
| `app.use(m)` | Every route of the app, and 404/405 answers |
| `mount(prefix, ...)` with `use(m)` inside | Routes of that mount |
| `app.get(path, handler, m1, m2)` | That route |
| a `RouteHook` | Routes it chooses, usually from handler annotations |

For one route the chain is composed once at boot, in this order: middleware of the enclosing apps
(outermost app first), then `RouteHook` contributions, then the route's own. The first in the list
runs first on the way in. Nothing is allocated per request for the chain.

### Ordering constraints

Plain middleware keeps declaration order. A `RouteHook` can give its contributions a key and
constraints, resolved at boot as a graph:

```java
MiddlewareNode.of(MiddlewareKey.of("security.authorize"), authorize)
        .after(MiddlewareKey.of("security.authenticate"))
        .beforeIfPresent(MiddlewareKey.of("audit"));
```

| Method | Meaning |
|---|---|
| `after(key)`, `before(key)` | The other node must exist, or the boot fails |
| `afterIfPresent(key)`, `beforeIfPresent(key)` | The constraint applies only if the other node exists |

The boot fails on a duplicate key on one route and on a cycle. Among unconstrained nodes the
original order is kept.

## ExchangeInterceptor

An interceptor wraps a whole route, outside the error boundary, so it observes the response after
the error handler has turned an exception into a status.

```java
public interface ExchangeInterceptor {
    Next wrap(Route route, Next exchange);
    default Next wrapFallback(Next exchange) { return exchange; }
}

app.add(ExchangeInterceptor.class, AccessLog.class);
```

`wrap` runs once per route at boot and returns the `Next` that will serve it. `wrapFallback` does the
same for requests no route matched. The first interceptor added is outermost. With none registered,
Flash builds the route without any wrapper.

| | Middleware | ExchangeInterceptor |
|---|---|---|
| Sees exceptions | Yes, can catch them | No, already converted to a response |
| Runs for 404 and 405 | Only app level middleware | Through `wrapFallback` |
| Knows the route | No | Yes, receives `Route` |
| Meant for | Application behavior | Metrics, tracing, logging |

## CORS

`Cors` is a middleware. Install it with `use`:

```java
app.use(Cors.allow("https://app.example")
        .methods("GET", "POST")
        .headers("Authorization", "Content-Type")
        .credentials());
```

| Method | Default | Effect |
|---|---|---|
| `Cors.allow(origins...)` | none named means any origin | Origins are scheme and host, for example `https://app.example` |
| `methods(...)` | `GET, HEAD, POST, PUT, PATCH, DELETE` | `Access-Control-Allow-Methods` in a preflight answer |
| `headers(...)` | none | `Access-Control-Allow-Headers` in a preflight answer, omitted when empty |
| `credentials()` | off | Adds `Access-Control-Allow-Credentials: true`. Requires named origins, otherwise `IllegalArgumentException` |

Behavior:

- A request without `Origin`, or from an origin not allowed, continues unchanged.
- For an allowed origin, `Access-Control-Allow-Origin` is `*` when no origins are named, otherwise
  the matched origin together with `Vary: Origin`.
- An `OPTIONS` request with `Access-Control-Request-Method` is answered with `204`,
  `Access-Control-Allow-Methods`, `Access-Control-Max-Age: 600` and, if configured,
  `Access-Control-Allow-Headers`. The route is not run.
- Because `use` also wraps unmatched requests, a preflight to a path with no `OPTIONS` route works.
