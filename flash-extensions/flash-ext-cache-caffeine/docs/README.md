# flash-ext-cache-caffeine

In-process caching backed by [Caffeine](https://github.com/ben-manes/caffeine). Implements
[`flash-ext-cache-core`](../../flash-ext-cache-core/docs/README.md).

## Dependency

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-cache-caffeine</artifactId>
    <version>${flash.version}</version>
</dependency>
```

## Quick start

```java
FlashApp.create(8080)
    .install(new CaffeineCacheExtension())
    .scan("dev.example.api");
```

```java
@GET("/api/users/{id}")
public final class GetUser extends RequestHandler {

    private Cache<String, User> users;
    private UserRepository repo;

    @Override protected void onInit() {
        repo  = require(UserRepository.class);
        users = require(CacheManager.class).build("users", spec -> spec
                .maxSize(10_000)
                .ttl(Duration.ofMinutes(10)));
    }

    @Override public Object handle(Request req, Response res) {
        return users.get(req.param("id"), repo::findById);
    }
}
```

The extension takes no configuration. Each cache declares its own size and TTL where it is built.

## Why Caffeine and not a `LinkedHashMap`

An LRU on top of `LinkedHashMap` is about sixty lines, and for a cache that is genuinely
low-traffic it is the right answer — `ConcurrentHashMap::computeIfAbsent` is one line and has no
hit rate to get wrong.

This module exists for the case where that stops being true. Caffeine's W-TinyLFU admission,
striped frequency counters and amortised eviction are not a weekend's work to reproduce, and the
failure mode of getting them wrong is a cache that is *slower* than no cache — lock contention on
every lookup, or an eviction policy that throws away exactly the entries you were about to want.

## Lifecycle

Caches are released through `FlashContext.onClose`, so `app.stop()` drops every entry. That is
invisible in production with one app per process and matters immediately under test, where many
apps start and stop in one JVM.

## Statistics

```java
CacheStats stats = users.stats();
stats.hitRate();   // 0.0 until something is looked up
```

Requires `recordStats()` on the spec. Without it you get `CacheStats.DISABLED`, which is honest
about being unmeasured rather than reporting zeroes that look like a cold cache.

`manager.names()` lists every cache built so far, for an ops endpoint.

## What this is not

**HTTP caching.** If what you want is for the *client* to stop asking — `Cache-Control`, `ETag`,
`304 Not Modified` — that is a middleware, not an object cache, and it saves the whole request
rather than the lookup inside it. Reach for that first: it is cheaper, and the two solve different
problems.

**A shared cache.** Every replica has its own. Two instances will hold different values for the
same key, and an invalidation on one does not reach the other. When that becomes a problem the
answer is a networked backend — see the note on `flash-ext-cache-redis` — and the semantics change
with it: a cache that can fail is no longer transparent.
