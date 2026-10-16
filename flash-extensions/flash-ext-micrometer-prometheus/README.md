# flash-ext-micrometer-prometheus

[`flash-ext-micrometer`](../flash-ext-micrometer/README.md) with a Prometheus registry and a scrape
endpoint.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-micrometer-prometheus</artifactId>
</dependency>
```

```java
app.install(new PrometheusModule());          // GET /metrics
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Options and the endpoint |
