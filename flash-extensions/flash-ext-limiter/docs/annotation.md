# @Limit annotation

Applies a rate limit to a **class-based** `RequestHandler`. The annotation is read once
per handler class at boot by the `LimiterExtension` annotation processor — zero overhead
at request time.

## Declaration

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Limit {
    String      key()        default "ip";
    int         requests();
    long        window();
    TimeUnit    windowUnit() default TimeUnit.SECONDS;
    LimitStrategy strategy() default LimitStrategy.FIXED_WINDOW;
}
```

## Fields

| Field | Type | Default | Description |
|---|---|---|---|
| `key` | `String` | `"ip"` | Name of the key resolver registered in `LimiterConfig` |
| `requests` | `int` | — | Maximum requests allowed per window (required) |
| `window` | `long` | — | Window duration in `windowUnit` units (required) |
| `windowUnit` | `TimeUnit` | `SECONDS` | Time unit for `window` |
| `strategy` | `LimitStrategy` | `FIXED_WINDOW` | Rate-limit algorithm |

## Basic usage

### 100 requests per second per IP (default)

```java
@Route(method = HttpMethod.GET, path = "/api/search")
@Limit(requests = 100, window = 1)
public class SearchHandler extends RequestHandler {
    public Object handle(Request req, Response res) {
        return searchService.query(req.query("q"));
    }
}
```

### 20 requests per minute per authenticated user

```java
@Route(method = HttpMethod.POST, path = "/api/report")
@Limit(key = "auth_user", requests = 20, window = 1, windowUnit = TimeUnit.MINUTES)
@Authenticated
public class ReportHandler extends RequestHandler {
    public Object handle(Request req, Response res) { ... }
}
```

Order of annotation processors: register `LimiterExtension` before `OidcExtension`
so the rate-limit middleware wraps the outer layer of the chain and fires before auth.

### Token bucket — absorb bursts

```java
@Route(method = HttpMethod.POST, path = "/api/upload")
@Limit(
    key        = "api_key",
    requests   = 50,
    window     = 1,
    windowUnit = TimeUnit.MINUTES,
    strategy   = LimitStrategy.TOKEN_BUCKET
)
public class UploadHandler extends RequestHandler { ... }
```

### Large window — 1000 requests per hour

```java
@Route(method = HttpMethod.GET, path = "/api/export")
@Limit(requests = 1000, window = 1, windowUnit = TimeUnit.HOURS)
public class ExportHandler extends RequestHandler { ... }
```

### Strict per-second limit on a public endpoint

```java
@Route(method = HttpMethod.GET, path = "/api/prices")
@Limit(requests = 10, window = 1, windowUnit = TimeUnit.SECONDS)
public class PriceHandler extends RequestHandler { ... }
```

## Combining @Limit with other annotations

`@Limit` composes naturally with `@Authenticated`, `@RolesAllowed`, and `@ApiOperation`.
Each annotation is processed by its own processor; Flash collects all middleware and
composes them in processor registration order.

When `flash-ext-openapi` is installed, `@Limit` also contributes OpenAPI response
headers (`X-RateLimit-*`) and `Retry-After` on `429` automatically.

```java
@Route(method = HttpMethod.DELETE, path = "/admin/users/{id}")
@Limit(key = "auth_user", requests = 5, window = 1, windowUnit = TimeUnit.MINUTES)
@RolesAllowed("admin")
@ApiOperation(summary = "Delete a user", tags = "admin")
public class DeleteUserHandler extends RequestHandler { ... }
```

Execution chain (outermost → handler):
`LimiterMiddleware → OidcRolesMiddleware → DeleteUserHandler`

## Fail-fast at boot

If `key` names a resolver not registered in `LimiterConfig`, the server refuses to start:

```
dev.relism.exceptions.InitializationException:
  Rate-limit resolver "auth_user" is not registered.
  Call LimiterConfig.registerResolver("auth_user", req -> ...) before install.
```

There is no silent fallback — a misconfigured rate limit is treated as a hard error.

## What happens on violation

```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit:     100
X-RateLimit-Remaining: 0
X-RateLimit-Reset:     1711750860
Retry-After:           1
Content-Type:          text/plain

Too Many Requests
```

The handler body is never invoked. See [http-headers.md](http-headers.md) for the full
header reference.
