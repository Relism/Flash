# flash-ext-cache-redis — planned

Not implemented. This directory holds the design so the decision is written down rather than
rediscovered; there is deliberately **no module, no pom and no source**, because an empty module
that builds an empty jar is dead weight in the reactor and in everyone's dependency tree.

Add it when there is a second replica that actually needs shared state.

## What it would implement

`CacheManager` and `Cache` from [`flash-ext-cache-core`](../../flash-ext-cache-core/docs/README.md),
so switching backend is an install-line change:

```java
.install(new RedisCacheModule(RedisConfig.of("redis://localhost:6379")))
```

## The part that is not a drop-in

`flash-ext-cache-caffeine` cannot fail. A networked cache can, and that changes the contract in
ways an adapter cannot hide:

- **`get(key, loader)` can fail before reaching the loader.** The honest default is to fall
  through to the loader and serve the value uncached, so Redis being down degrades throughput
  rather than taking the application with it. That has to be a decision, not an accident.
- **Values must be serialized.** Caffeine stores references. A `byte[]` codec belongs in the spec,
  and the natural default is whatever `flash-ext-jackson` is already configured with.
- **`invalidateAll()` is not free.** Against a shared keyspace it is either a scan or a key
  prefix per cache name. The prefix is the right answer, and it means cache names become part of
  the wire contract.
- **Stats are per-client, not per-cache.** Hit rate stays meaningful; eviction count does not,
  because Redis evicts on its own policy.

## Why it is not built yet

Nothing in the codebase has two replicas sharing cache state. Building it now would mean choosing
a client library, a serialization format and a failure policy with no real usage to check them
against — and the failure policy in particular is the kind of decision that is wrong until a
production incident tells you otherwise.
