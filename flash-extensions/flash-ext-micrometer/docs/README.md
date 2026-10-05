# flash-ext-micrometer

Standard Micrometer HTTP server metrics for Flash. Supply any `MeterRegistry`; the module exposes it
as an application service and records one `http.server.requests` timer per route, method and status.

```java
MeterRegistry registry = new SimpleMeterRegistry();

App app = App.create()
    .install(new MicrometerModule(registry))
    .get("/users/{id}", users::get);
```

## Automatic HTTP metric

The timer has the conventional `method`, `uri`, `status` and `outcome` tags. `uri` is the bounded
route template (`/users/{id}`), never the concrete request path. Misses use `NOT_FOUND`,
`METHOD_NOT_ALLOWED` or `UNKNOWN`. The recorded status is the response after `app.onError` has run.
WebSocket routes are not HTTP-timed. This is the complete automatic instrumentation: JVM, process
and business metrics remain explicit.

## Custom metrics

`MicrometerModule` adds its registry as a `MeterRegistry` service. A handler built by Flash can
therefore ask for it in its constructor; no lookup, static registry or additional wiring is needed.

```java
public final class CreateOrder implements Handler {
    private final Counter created;
    private final Timer duration;

    public CreateOrder(MeterRegistry registry) {
        created = registry.counter("orders.created");
        duration = registry.timer("orders.creation");
    }

    @Override
    public Object handle(Request request, Response response) throws Exception {
        long started = System.nanoTime();
        try {
            createOrder(request);
            created.increment();
            return response.status(201);
        } finally {
            duration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}

app.install(new MicrometerModule(registry))
   .post("/orders", CreateOrder.class);
```

The same constructor injection works when `PrometheusModule` is installed: that module installs
`MicrometerModule` and exposes its Prometheus registry through the same service.

A lambda is already built by the application, so it captures the registry—or a meter created from
it—directly:

```java
Counter created = registry.counter("orders.created");

app.install(new MicrometerModule(registry))
   .post("/orders", (request, response) -> {
       createOrder(request);
       created.increment();
       return response.status(201);
   });
```

Keep meters in fields or captured variables instead of looking them up for every request. Record
durations as primitive nanoseconds, and keep tag values bounded: a status, operation or region is a
tag; a user ID, concrete URL or arbitrary input is not.

Nothing is sampled and there is no default configuration to tune. A route can be excluded by its
exact template when infrastructure exposes its own endpoint:

```java
app.install(new MicrometerModule(registry).exclude("/internal/metrics"));
```

The extension creates meter series lazily because statuses cannot be known at boot. After a
route/method/status series has been seen once, recording uses primitive nanoseconds, array lookups
and Micrometer's primitive `Timer.record(long, TimeUnit)` API: the extension allocates no object and
copies no request data on that path. Without the module, the core installs no interceptor wrapper or
request-path branch.
