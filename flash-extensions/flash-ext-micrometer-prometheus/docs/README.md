# flash-ext-micrometer-prometheus

The ready-to-run Prometheus integration: it installs `flash-ext-micrometer`, creates a Prometheus
registry and exposes it at `/metrics`.

```java
App app = App.create()
    .install(new PrometheusModule())
    .scan("com.example.api");
```

Add only this dependency for Prometheus; it brings the generic Micrometer extension transitively.
The scrape route excludes itself from HTTP metrics and streams the registry directly to the Flash
response instead of building and UTF-8-encoding an intermediate scrape string.

The defaults need no configuration. Override only what differs:

```java
// Different endpoint
new PrometheusModule("/internal/metrics");

// Registry configured with application-specific filters or common tags
new PrometheusModule(registry, "/internal/metrics");
```

The endpoint emits Prometheus text exposition with
`text/plain; version=0.0.4; charset=utf-8`.

The registry is also available through Flash constructor injection for custom application metrics.
For a lambda, supply the registry and capture the meter directly:

```java
PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
Counter created = registry.counter("orders.created");

App app = App.create()
    .install(new PrometheusModule(registry, "/metrics"))
    .post("/orders", (request, response) -> {
        created.increment();
        return response.status(201);
    });
```

See [`flash-ext-micrometer`](../../flash-ext-micrometer/docs/README.md#custom-metrics) for the
constructor-injected handler pattern and hot-path guidance.
