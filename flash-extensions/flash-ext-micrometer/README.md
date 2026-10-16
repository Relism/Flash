# flash-ext-micrometer

HTTP server metrics for Flash with [Micrometer](https://micrometer.io): a timer per route, method and
status, written to a registry you supply.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-micrometer</artifactId>
</dependency>
```

```java
app.install(new MicrometerModule(registry));
```

For Prometheus use [`flash-ext-micrometer-prometheus`](../flash-ext-micrometer-prometheus/README.md).

| Page | Content |
|---|---|
| [docs/metrics.md](docs/metrics.md) | The `http.server.requests` timer and its tags |
