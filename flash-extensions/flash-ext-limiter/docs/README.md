# flash-ext-limiter

Rate limiting for the Flash HTTP server. Zero-allocation hot-path, lock-free counters,
pluggable key resolvers, and two built-in algorithms.

## What it provides

| Component | Description |
|---|---|
| `@Limit` | Annotation for class-based handlers — processed once at boot |
| `Guard` | Programmatic middleware factory for lambda routes |
| `LimiterConfig` | Resolver registry — map string names to key-extraction lambdas |
| `FIXED_WINDOW` | Clock-aligned counter reset; minimal memory |
| `TOKEN_BUCKET` | Continuous refill; absorbs bursts smoothly |

## Dependency

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-limiter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Quick start

```java
// Default install — only the built-in "ip" resolver available
FlashApp.create(8080)
    .install(new LimiterExtension())
    .scan("com.example.handlers");
```

```java
// With custom resolvers
LimiterConfig conf = new LimiterConfig()
    .registerResolver("auth_user", req ->
        SecurityIdentity.current() != null ? SecurityIdentity.current().principal().name() : "anonymous");

FlashApp.create(8080)
    .install(new LimiterExtension(conf))
    .scan("com.example.handlers");
```

## Installation order

Install `LimiterExtension` **before** authentication extensions. Rate-limit checks
then short-circuit over-limit requests before expensive token validation runs.

```java
app.install(new LimiterExtension(conf))   // ← first
   .install(new OidcExtension(oidcConf))  // ← second
   .scan("com.example");
```

## Docs

| File | Contents |
|---|---|
| [key-resolvers.md](key-resolvers.md) | Resolver registration, built-in defaults, custom logic |
| [annotation.md](annotation.md) | `@Limit` reference — all fields and examples |
| [guard.md](guard.md) | `Guard` for lambda routes — all overloads |
| [strategies.md](strategies.md) | `FIXED_WINDOW` vs `TOKEN_BUCKET` — algorithm reference |
| [http-headers.md](http-headers.md) | HTTP compliance — headers and 429 response |
