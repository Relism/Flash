# flash-ext-cache-core

The cache contract: named caches obtained from a `CacheManager`, each with a size bound, expiry and
optional hit statistics. It contains no implementation. Install a backend such as
[`flash-ext-cache-caffeine`](../flash-ext-cache-caffeine/README.md).

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-cache-core</artifactId>
</dependency>
```

```java
final class UserLookup extends RequestHandler {
    private final Cache<Long, User> users;

    UserLookup(CacheManager caches, UserRepository repository) {
        this.users = caches.build("users", spec -> spec.maxSize(10_000).ttl(Duration.ofMinutes(10)));
        ...
    }
}
```

| Page | Content |
|---|---|
| [docs/api.md](docs/api.md) | `CacheManager`, `Cache`, `CacheSpec`, `CacheStats` |
