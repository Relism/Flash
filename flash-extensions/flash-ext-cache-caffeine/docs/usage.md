# Using Caffeine caches

`CaffeineCacheModule` registers a `CacheManager` service. It takes no configuration: each cache
declares its size and expiry where it is built.

## Mapping from CacheSpec

| `CacheSpec` | Caffeine |
|---|---|
| `maxSize` (not negative) | `maximumSize` |
| `ttl` | `expireAfterWrite` |
| `ttlAfterAccess` | `expireAfterAccess` |
| `recordStats()` | `recordStats()` |

`Cache.get(key, loader)` is Caffeine's own `get(key, mappingFunction)`, which guarantees one load per
key across concurrent callers. Other methods delegate directly.

`stats()` reports `hitCount`, `missCount` and `evictionCount` when stats were requested.

## Sharing and conflicts

Caches are kept by name. Calling `build` again for an existing name returns the same cache, provided
the spec is identical (`maxSize`, `ttl`, `ttlAfterAccess` and stats). A different spec throws
`IllegalStateException`:

```
Cache 'users' already exists as maxSize=10000 ttl=PT10M ... but was requested as maxSize=500 ...
```

## Lifecycle

The manager is closed when the app stops, which invalidates every cache and forgets them. Caches do
not survive a restart and are not shared between instances. For shared state use a different backend.
