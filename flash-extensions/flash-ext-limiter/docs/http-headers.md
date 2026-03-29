# HTTP headers and 429 response

The extension injects standard rate-limit headers on **every** request — both allowed
and rejected. Clients can use these headers to implement back-off logic without waiting
for a 429.

## Response headers

| Header | Type | Description |
|---|---|---|
| `X-RateLimit-Limit` | integer | Maximum requests allowed in the current window |
| `X-RateLimit-Remaining` | integer | Requests remaining in the current window (≥ 0) |
| `X-RateLimit-Reset` | Unix timestamp (s) | When the quota resets or the next token arrives |
| `Retry-After` | seconds | **Only on 429** — how long to wait before retrying (≥ 1) |

### Example — allowed request

```
HTTP/1.1 200 OK
X-RateLimit-Limit:     100
X-RateLimit-Remaining: 73
X-RateLimit-Reset:     1711750860
Content-Type: application/json
```

### Example — rejected request (429)

```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit:     100
X-RateLimit-Remaining: 0
X-RateLimit-Reset:     1711750860
Retry-After:           1
Content-Type: text/plain

Too Many Requests
```

## Header semantics by strategy

### FIXED_WINDOW

| Header | Value |
|---|---|
| `X-RateLimit-Reset` | Unix timestamp of the **next window start** (aligned to clock) |
| `Retry-After` | Seconds until `X-RateLimit-Reset` (minimum 1) |

At a 1-second window boundary `Retry-After` will typically be `1`.

### TOKEN_BUCKET

| Header | Value |
|---|---|
| `X-RateLimit-Remaining` | Current token count (may increase between requests due to refill) |
| `X-RateLimit-Reset` | Estimated Unix timestamp when the **next token arrives** |
| `Retry-After` | Milliseconds-precise estimate converted to seconds (minimum 1) |

Because the token bucket refills continuously, `X-RateLimit-Reset` is a near-future
timestamp rather than an aligned window boundary.

## Retry-After precision

`Retry-After` is computed as:

```
retryAfter = max(1, X-RateLimit-Reset - currentTimeSeconds)
```

The minimum value is always `1` second — RFC 7231 discourages `Retry-After: 0` as it
encourages instant retry loops.

## Client-side back-off example (Java)

```java
HttpResponse<String> res = client.send(request, BodyHandlers.ofString());

if (res.statusCode() == 429) {
    String retryAfter = res.headers().firstValue("Retry-After").orElse("1");
    long waitMs = Long.parseLong(retryAfter) * 1000L;
    Thread.sleep(waitMs);
    // retry...
}
```

## Client-side back-off example (JavaScript fetch)

```js
const res = await fetch('/api/search?q=flash');

if (res.status === 429) {
    const retryAfter = parseInt(res.headers.get('Retry-After') ?? '1', 10);
    await new Promise(r => setTimeout(r, retryAfter * 1000));
    // retry...
}
```

## Monitoring / alerting

`X-RateLimit-Remaining` can be scraped by a metrics agent to track approaching limits
before they hit 429:

- `remaining / limit < 0.1` → warning (less than 10% quota left)
- `status == 429` → rate-limit violation counter increment

If `flash-ext-limiter` is used together with a future metrics extension, the 429 rate
per resolver key is a natural signal for abuse detection or auto-scaling.

## Header injection timing

Headers are injected **before** calling `next.handle(req, res)` on allowed requests,
and **instead of** calling it on rejected requests. This means:

- Handlers cannot accidentally overwrite `X-RateLimit-*` headers (they are set first,
  but handlers that call `res.header(...)` with the same name will add a second value —
  avoid this by not setting these headers manually).
- On 429, the handler body is never executed — no side effects occur.

## Integration with Swagger UI (flash-ext-openapi)

Rate-limit headers are not currently injected into the OpenAPI spec. If you want to
document them, add them manually via `@ApiOperation` on the handler class using the
response headers section of the OpenAPI spec.
