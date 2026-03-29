# Guard — programmatic rate limiting for lambda routes

`Guard` is the rate-limit API for lambda (inline) route registrations. It produces
a `Middleware` that is composed once at route-wiring time — the resolver lambda is
captured directly into the closure, with no map lookup on the request hot-path.

## Obtaining Guard

`Guard` is provided in the `FlashContext` after `LimiterExtension` is installed:

```java
Guard guard = app.ctx().require(Guard.class);
```

Or inside another extension:

```java
public void install(FlashRegistrar app, FlashContext ctx) {
    Guard guard = ctx.require(Guard.class);
    // ...
}
```

## API

```java
// Fixed window (default strategy)
Middleware limit(String resolverKey, int requests, long window, TimeUnit unit)

// Explicit strategy
Middleware limit(String resolverKey, int requests, long window, TimeUnit unit, LimitStrategy strategy)
```

Both overloads:
- Resolve the named key lambda **once** at call time (fail-fast if unknown).
- Return a stateless `Middleware` whose closure captures the lambda and `LimitConfig` directly.
- Share the `BucketStore` with all other rules registered through this `LimiterExtension` instance.

## Examples

### Simple per-IP limit on a lambda route

```java
Guard guard = app.ctx().require(Guard.class);

app.get("/api/search", (req, res) -> searchService.query(req.query("q")))
   .with(guard.limit("ip", 100, 1, TimeUnit.SECONDS));
```

### Per authenticated user — token bucket

```java
app.post("/api/export", (req, res) -> exportService.run(req))
   .with(guard.limit("auth_user", 10, 1, TimeUnit.MINUTES, LimitStrategy.TOKEN_BUCKET));
```

### Chaining with other middleware

`Guard.limit(...)` returns a plain `Middleware`, so it composes with `Middleware.of()`
and `.andThen()` exactly like any other middleware:

```java
Middleware secured = Middleware.of(
    guard.limit("ip", 200, 1, TimeUnit.SECONDS),   // ← outermost: runs first
    oidc.protect()
);

app.get("/dashboard", handler).with(secured);
```

Or with `.andThen()` for two middlewares:

```java
app.get("/dashboard", handler)
   .with(guard.limit("ip", 200, 1, TimeUnit.SECONDS).andThen(oidc.protect()));
```

### Different limits on the same path by method

```java
// Read: 500/s; Write: 20/s
app.get("/api/items",  readHandler) .with(guard.limit("ip", 500, 1, TimeUnit.SECONDS));
app.post("/api/items", writeHandler).with(guard.limit("ip",  20, 1, TimeUnit.SECONDS));
```

Each `.with(guard.limit(...))` call creates an independent bucket store key namespace —
GET and POST requests to `/api/items` share the same IP bucket only if you share the same
`Middleware` instance. Using two `guard.limit(...)` calls creates **two independent buckets**.

### Reusing a middleware instance across routes

To share a single bucket pool across multiple routes (treating them as one combined limit):

```java
Middleware sharedIpLimit = guard.limit("ip", 1000, 1, TimeUnit.MINUTES);

app.get("/api/items",      handler1).with(sharedIpLimit);
app.get("/api/items/{id}", handler2).with(sharedIpLimit);
app.post("/api/items",     handler3).with(sharedIpLimit);
```

All three routes now draw from the same per-IP bucket — 1000 combined requests per minute.

### Inside an extension

```java
public class MyApiExtension implements FlashExtension {
    public void install(FlashRegistrar app, FlashContext ctx) {
        Guard guard = ctx.require(Guard.class);  // LimiterExtension must be installed first

        Middleware ipLimit = guard.limit("ip", 60, 1, TimeUnit.SECONDS);

        app.get("/api/status",  statusHandler) .with(ipLimit);
        app.get("/api/metrics", metricsHandler).with(ipLimit);
    }
}
```

### Large window

```java
app.get("/api/export", exportHandler)
   .with(guard.limit("api_key", 50, 24, TimeUnit.HOURS));
```

## Fail-fast

If the resolver name is not registered, `guard.limit(...)` throws immediately
(at wiring time, not at request time):

```
InitializationException: Rate-limit resolver "auth_user" is not registered.
```

## Comparison: Guard vs @Limit

| | `@Limit` | `Guard.limit(...)` |
|---|---|---|
| Route style | Class-based `RequestHandler` | Lambda `(req, res) -> ...` |
| Configuration | Annotation fields | Method arguments |
| Where resolved | `AnnotationProcessor` at `scan()` | `guard.limit(...)` call at wiring |
| Hot-path overhead | Zero | Zero |
| Fail-fast | Yes | Yes |
| Composable with `Middleware.of()` | Via annotation processor order | Yes, directly |
