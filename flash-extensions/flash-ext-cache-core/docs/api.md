# Cache API

## CacheManager

A service provided by a backend. Take it as a constructor parameter.

| Method | Behavior |
|---|---|
| `build(name, spec)` | Creates the named cache, or returns the existing one. The `spec` lambda configures a `CacheSpec` |
| `cache(name)` | The named cache, or `null` if it was never built |
| `names()` | The names built so far |

`build` is idempotent per name, so several handlers can share one cache without agreeing on who
creates it. A second call with a different spec for the same name is a configuration mistake and
throws.

## CacheSpec

| Method | Meaning |
|---|---|
| `maxSize(long)` | Entries kept before the backend evicts. Negative means unbounded (the default) |
| `ttl(Duration)` | Entries expire this long after they were written |
| `ttlAfterAccess(Duration)` | Entries expire this long after they were last read or written |
| `recordStats()` | Count hits and misses. Off by default, because it costs atomic increments on every lookup |

A spec that sets neither `maxSize` nor an expiry gives a cache that grows forever. Set at least one.

## Cache

```java
Cache<Long, User> users = caches.build("users", spec -> spec.maxSize(10_000).ttl(Duration.ofMinutes(10)));
User user = users.get(id, repository::findById);
```

| Method | Behavior |
|---|---|
| `get(key, loader)` | The cached value, or the loader's result stored under the key. The loader runs at most once per key across concurrent callers; the others wait. A `null` result is not stored and `null` is returned |
| `getIfPresent(key)` | The value or `null`. Never calls a loader |
| `put(key, value)` | Stores, replacing any entry |
| `invalidate(key)` | Drops one key |
| `invalidateAll()` | Drops every entry |
| `estimatedSize()` | Approximate count. Eviction is asynchronous in most backends |
| `stats()` | `CacheStats`, or `CacheStats.DISABLED` (all zero) if `recordStats` was not set |

A cache can be held in a field and shared by all threads. Keys need a stable `equals` and `hashCode`.

## CacheStats

A record of `hits`, `misses` and `evictions`. `hitRate()` is hits over lookups, and `0` before any
lookup.
