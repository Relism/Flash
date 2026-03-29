# Key Resolvers

A **key resolver** is a lambda `Request → String` that extracts the partition key used
to identify who a rate limit applies to. Each unique key value gets its own independent
bucket — so `"ip"` limits per client address, `"auth_user"` limits per logged-in user, etc.

## Built-in resolver: `"ip"`

Always present. Cannot be removed; can be overridden with `registerResolver("ip", ...)`.

Resolution order:
1. `X-Forwarded-For` header — first address in the comma-separated list (client behind proxy)
2. `X-Real-IP` header — single forwarded IP (nginx `proxy_set_header X-Real-IP`)
3. `req.remoteAddress().getAddress().getHostAddress()` — direct socket address, zero allocation
   (the `InetSocketAddress` already exists from `ServerSocket.accept()`; only `getHostAddress()`
   allocates a String, and only when the first two headers are absent)
4. `"unknown"` — only if `remoteAddress()` is null (test-constructed requests)

```java
// Override the built-in "ip" resolver to trust only the last hop in X-Forwarded-For
conf.registerResolver("ip", req -> {
    String xff = req.header("X-Forwarded-For");
    if (xff != null) {
        String[] parts = xff.split(",");
        return parts[parts.length - 1].strip();   // last = most recent proxy
    }
    return req.header("X-Real-IP") != null ? req.header("X-Real-IP").strip() : "unknown";
});
```


## Registering custom resolvers

```java
LimiterConfig conf = new LimiterConfig();
```

### By authenticated user (OIDC / ClaimsHolder)

```java
conf.registerResolver("auth_user", req ->
    ClaimsHolder.exists() ? ClaimsHolder.user().sub() : "anonymous");
```

Requests from unauthenticated users share the `"anonymous"` bucket. If you want
unauthenticated requests to be unlimited, pair this resolver with `@Limit` only on
handlers that are already protected by `@Authenticated`.

### By API key header

```java
conf.registerResolver("api_key", req -> {
    String key = req.header("X-Api-Key");
    return key != null ? key : "none";
});
```

### By tenant (multi-tenant SaaS)

```java
conf.registerResolver("tenant", req -> {
    // Extract from subdomain: acme.api.example.com → "acme"
    String host = req.header("Host");
    if (host == null) return "unknown";
    int dot = host.indexOf('.');
    return dot > 0 ? host.substring(0, dot) : host;
});
```

### By IP + path (per-endpoint per-IP)

Combines two dimensions into a single key string:

```java
conf.registerResolver("ip_path", req -> {
    String ip   = req.header("X-Forwarded-For");
    if (ip == null) ip = "unknown";
    int comma = ip.indexOf(',');
    if (comma > 0) ip = ip.substring(0, comma).strip();
    return ip + "|" + req.path();
});
```

### Composite: role-based bucket size

One resolver, two different `@Limit` thresholds on two handler classes. The resolver
returns the same key for the same user regardless of endpoint; the limit is set per handler.

```java
conf.registerResolver("auth_user", req ->
    ClaimsHolder.exists() ? ClaimsHolder.user().sub() : "anon");
```

```java
@Limit(key = "auth_user", requests = 1000, window = 1)   // privileged endpoint
public class AdminReportHandler extends RequestHandler { ... }

@Limit(key = "auth_user", requests = 20,   window = 1)   // public endpoint
public class PublicSearchHandler extends RequestHandler { ... }
```

The two handlers maintain **independent buckets** for the same user — each `@Limit`
annotation gets its own `BucketStore`.

## Resolver contract

```java
@FunctionalInterface
public interface KeyResolver {
    String resolve(Request req);  // must never return null; return "unknown" as fallback
}
```

- Must not return `null` — a null key will throw `NullPointerException` inside `ConcurrentHashMap`.
- Must be **thread-safe** — called concurrently from virtual threads.
- Should be **fast** — it runs on every request for every rate-limited route.
- No state should be mutated — treat `Request` as read-only.

## Fail-fast validation

If a `@Limit` annotation or `guard.limit(...)` call references a resolver name that was never
registered, the server **refuses to start** with `InitializationException`:

```
InitializationException: Rate-limit resolver "auth_user" is not registered.
Call LimiterConfig.registerResolver("auth_user", req -> ...) before install.
```

This check happens at boot time (annotation processor / Guard wiring), not at request time.

## Registration API

```java
LimiterConfig conf = new LimiterConfig()
    .registerResolver("auth_user", req -> ...)
    .registerResolver("tenant",    req -> ...)
    .registerResolver("api_key",   req -> ...);

app.install(new LimiterExtension(conf));
```

`registerResolver` returns `this` for fluent chaining. Calling it with an existing name
**replaces** the previous resolver — this is how you override the built-in `"ip"` resolver.
