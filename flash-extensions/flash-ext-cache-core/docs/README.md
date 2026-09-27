# flash-ext-cache-core

The caching contract, shared across backends. Like `flash-ext-data-core`, this module talks to
nothing: it defines the abstractions and a backend implements them.

## Components

- `Cache<K, V>` — a named cache. `get(key, loader)` is the method that matters.
- `CacheManager` — creates and hands back named caches.
- `CacheSpec` — size and expiry for one cache.
- `CacheStats` — hit/miss/eviction counters.

Install a backend, not this module: [`flash-ext-cache-caffeine`](../../flash-ext-cache-caffeine/docs/README.md)
for in-process caching.

## The one shape that matters

```java
User user = users.get(id, repo::findById);
```

Compute-if-absent is the only cache operation most code needs, and the only one that is hard to
get right — the loader runs **once per key** across concurrent callers, and the rest wait rather
than each computing their own. `getIfPresent`, `put`, `invalidate` and `invalidateAll` exist for
what it cannot express.

A loader returning `null` stores nothing and returns `null`. Caching absence is a decision, not a
default; wrap it in an `Optional` or a sentinel if you want it.

## Naming and sharing

`CacheManager.build(name, spec)` is idempotent per name: two handlers asking for `"users"` get one
cache, not two, so nobody has to coordinate who creates it first.

If they disagree about the spec, that throws. The alternative is a cache whose size depends on
which handler happened to initialise first, which is the kind of bug that only shows up under
load.

## Specs

```java
CacheSpec.of()
    .maxSize(10_000)
    .ttl(Duration.ofMinutes(10))
    .recordStats();
```

Every field is optional, but a spec that sets neither `maxSize` nor `ttl` is an unbounded cache
that never expires — a memory leak wearing a hat. Set at least one.

`recordStats()` is off by default: counting costs a pair of atomic increments on every lookup, and
a cache nobody is measuring should not pay for numbers nobody reads. Without it, `stats()` returns
`CacheStats.DISABLED` rather than silently zero.

## Where the spec lives

On the cache, at the point it is built — not in application config. Size and TTL are properties of
*what is being cached*, not of the process doing the caching, and a TTL in a config file is a TTL
nobody can relate back to the data it governs.

## Writing a backend

Implement `CacheManager` and `Cache`, and add the manager from a module:
`app.add(CacheManager.class, MyCacheManager.class)`. Make the manager `AutoCloseable`: the app
closes it on stop, so a stopped app does not keep its values alive.
