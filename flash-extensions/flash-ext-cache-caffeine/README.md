# flash-ext-cache-caffeine

An in-process `CacheManager` backed by [Caffeine](https://github.com/ben-manes/caffeine). It implements
[`flash-ext-cache-core`](../flash-ext-cache-core/README.md).

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-cache-caffeine</artifactId>
</dependency>
```

```java
app.install(new CaffeineCacheModule());
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Behavior of the manager and its caches |
