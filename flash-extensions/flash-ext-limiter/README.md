# flash-ext-limiter

Rate limiting as middleware, with three algorithms and per-client keys. Counters are in memory and
per instance.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-limiter</artifactId>
</dependency>
```

```java
app.install(new LimiterModule());

@GET("/search")
@Limit(requests = 100, window = 1)           // 100 requests per second per client address
final class Search extends RequestHandler { ... }
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | `@Limit`, middleware form, keys, responses |
| [docs/strategies.md](docs/strategies.md) | The three algorithms |
