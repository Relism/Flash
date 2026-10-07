# Using the limiter

## On a handler class

```java
@Limit(key = "user", requests = 20, window = 1, windowUnit = TimeUnit.MINUTES,
       strategy = LimitStrategy.TOKEN_BUCKET)
final class Expensive extends RequestHandler { ... }
```

| Attribute | Default | Meaning |
|---|---|---|
| `requests` | required | Requests allowed per window |
| `window` | required | Window length |
| `windowUnit` | `SECONDS` | Unit of `window` |
| `key` | `"ip"` | Name of the resolver that tells clients apart |
| `strategy` | `FIXED_WINDOW` | `FIXED_WINDOW`, `SLIDING_WINDOW` or `TOKEN_BUCKET` |

`@Limit` is read when the app boots, for the handler's own class. An unknown `key` fails the boot.

## As middleware

```java
LimiterModule limiter = new LimiterModule().resolver("user", req -> userIdOf(req));
app.install(limiter)
   .get("/search", search, limiter.limit("ip", 50, 1, TimeUnit.SECONDS))
   .use(limiter.limit("ip", 1000, 1, TimeUnit.MINUTES));
```

`limit(key, requests, window, unit[, strategy])` builds the middleware. Every call, and every
`@Limit`, has its own counters, so two routes with the same rule count separately.

## Keys

A resolver maps a request to the string a request is counted under.

```java
limiter.resolver("api-key", req -> req.header("X-Api-Key"));
```

`"ip"` is built in and returns `Request.clientAddress()`, which honors
[`trustedProxies`](../../../flash/docs/config.md#limits). A resolver must return a non-null key.

Each rule tracks at most `maxKeys` keys (100,000 by default, `new LimiterModule().maxKeys(n)`). When
the limit is reached, keys idle for a full window are dropped; if all are still active, new keys
share one overflow counter, so a flood of invented keys meets the limit rather than the heap.

## Response

Every response from a limited route carries:

| Header | Value |
|---|---|
| `X-RateLimit-Limit` | The rule's request limit |
| `X-RateLimit-Remaining` | Requests left |
| `X-RateLimit-Reset` | Epoch second when the quota resets |

A request over the limit is answered `429` with `Retry-After` (seconds, at least 1) by
`HttpException.tooManyRequests`.

## OpenAPI

With `flash-ext-openapi`, each handler with `@Limit` documents the three headers on every response
and a `429` with `Retry-After`. Middleware added with `limit(...)` is not documented, because it is
not an annotation on the handler.
