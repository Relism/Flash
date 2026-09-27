# flash-ext-limiter

Rate limits for Flash: lock-free counters, no allocation per request once a key is known.

```java
LimiterModule limiter = new LimiterModule()
        .resolver("user", req -> identity(req));    // "ip" is built in

App.create()
    .install(limiter)
    .get("/search", search, limiter.limit("ip", 50, 1, TimeUnit.SECONDS))
    .scan("com.example.api");
```

```java
@POST("/reports")
@Limit(key = "user", requests = 20, window = 1, windowUnit = TimeUnit.MINUTES, strategy = LimitStrategy.TOKEN_BUCKET)
public final class CreateReport implements Handler { ... }
```

`@Limit` on a handler class and `limit(...)` on any route do the same thing: each rule counts in
its own buckets, by the key its resolver returns. A key no resolver answers to fails the boot.
`"ip"` is `Request.clientAddress()`, which honours `X-Forwarded-For` only from the proxies listed
in `ServerConfig.trustedProxies`.

Install the limiter before security modules: route hooks add middleware in install order, so an
over-limit request is refused before its credentials are checked.

## Strategies

| `LimitStrategy` | |
|---|---|
| `FIXED_WINDOW` (default) | a counter per clock-aligned window; up to twice the limit across a boundary |
| `SLIDING_WINDOW` | the previous window's count weighted by how much of it still overlaps; no boundary burst, a few percent of error |
| `TOKEN_BUCKET` | `requests` tokens refilled continuously over `window`; an idle client can burst |

## Memory

One rule tracks at most `maxKeys(int)` keys, 100 000 by default. Past that, keys idle for a
whole window are dropped; if none is, new keys share one overflow bucket, so a flood of invented
keys meets the limit instead of the heap.

## Responses

Every limited response carries `X-RateLimit-Limit`, `X-RateLimit-Remaining` and
`X-RateLimit-Reset` (the epoch second the quota resets at). An over-limit request gets
`429 Too Many Requests` with `Retry-After` in seconds.

With `flash-ext-openapi` on the classpath, every `@Limit`ed operation documents those headers and
its 429.
