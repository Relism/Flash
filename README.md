# Flash

A high-performance HTTP/1.1 server library for Java 21, built around virtual threads and a zero-allocation FSM router.

## Modules

| Module | Description |
|---|---|
| `flash` | Core server library — router, request parser, HTTP I/O transport |
| `flash-extensions/flash-ext-jackson` | Jackson JSON integration |
| `flash-extensions/flash-ext-openapi` | OpenAPI 3.0 spec + Swagger UI |
| `flash-extensions/flash-ext-oidc` | OIDC Authorization Code + PKCE flow |
| `flash-bench` | Demo harness (OIDC + OpenAPI + Jackson) |

## Requirements

- Java 21+
- Maven 3.8+

## Quick start

```java
FlashApp.create(8080)
    .get("/ping", (req, res) -> "pong")
    .start();
```

With full configuration:

```java
FlashApp.create(
    FlashConfiguration.builder()
        .port(8080)
        .host("0.0.0.0")
        .maxHeaderBufferSize(65536)
        .build()
)
.get("/ping", (req, res) -> "pong")
.start();
```

## Route registration

### Lambda routes

```java
FlashApp app = FlashApp.create(8080);

app.get("/hello", (req, res) -> "world");

app.post("/echo", (req, res) -> {
    byte[] body = req.body().bytes();
    return res.status(200).body(body);
});

app.get("/users/{id}", (req, res) -> {
    String id = req.pathParam("id");
    return "user:" + id;
});
```

### Class-based handlers

Extend `RequestHandler` (or a subclass like `JacksonHandler`) and annotate with `@Route`:

```java
@Route(method = HttpMethod.GET, path = "/api/users")
public class ListUsers extends JacksonHandler {
    @Override
    public Object handle(Request req, Response res) throws Exception {
        return json(res, List.of("alice", "bob"));
    }
}

// Register:
app.register(new ListUsers());
```

### Middleware

Apply middleware via `.with()` on the `RouteHandle` returned by any registration call:

```java
Middleware authCheck = next -> (req, res) -> {
    if (req.header("Authorization") == null)
        return res.status(401).body("Unauthorized");
    return next.handle(req, res);
};

app.get("/secure", (req, res) -> "secret data")
   .with(authCheck);
```

Multiple middlewares are composed outermost-first (left-to-right in the call):

```java
app.get("/admin", handler).with(logging, auth, rateLimit);
// execution order: logging → auth → rateLimit → handler
```

### Classpath scan

Scans a package for classes that extend `RequestHandler` and carry `@Route`. Each is
instantiated via its public no-arg constructor:

```java
app.scan("dev.example.handlers");
```

### Namespace mounting

Mount a scoped sub-router under a prefix. All routes registered inside the scope get the
prefix prepended automatically. The scope inherits the parent's extension context (annotation
processors, services):

```java
app.mount("/api", scope -> {
    scope.get("/health", (req, res) -> "ok");          // → GET /api/health
    scope.register(new UserHandler());                  // @Route(path="/users") → GET /api/users
    scope.scan("dev.example.api");
});
```

## Extensions

Extensions are installed before route registration. Each extension receives the `FlashRegistrar`
and `ExtensionContext` — it can register routes, expose services, and register annotation processors.

```java
FlashApp.create(8080)
    .install(new JacksonExtension())
    .install(new OpenApiExtension("/openapi", "My API", "1.0.0"))
    .install(new OidcExtension(oidcConfig))
    .register(new MyHandler())
    .start();
```

See extension-specific READMEs for full details:
- [`flash-ext-jackson`](flash-extensions/flash-ext-jackson/README.md)
- [`flash-ext-openapi`](flash-extensions/flash-ext-openapi/README.md)
- [`flash-ext-oidc`](flash-extensions/flash-ext-oidc/README.md)

## Error handlers

```java
app.onNotFound((req, res) -> res.status(404).body("Not found: " + req.path()));

app.onException((ex, req, res) -> {
    if (ex instanceof IllegalArgumentException)
        return res.status(400).body(ex.getMessage());
    return res.status(500).body("Internal error");
});
```

## FlashConfiguration

| Field | Default | Description |
|---|---|---|
| `port` | — | TCP port to bind |
| `host` | `"0.0.0.0"` | Bind address |
| `maxHeaderBufferSize` | `65536` | Max size of the header buffer (bytes) |

## Architecture

```
ServerSocket.accept()
  → RequestParser.parse()       # zero-alloc header parsing, buffer reuse across keep-alive
  → GlobalRouter.route()        # two-tier: mounted sub-routers (longest prefix) then FastPathRouterImpl
  → RequestHandler.handle()     # user handler; return value sets body
  → Request.drain()             # consume unread body for keep-alive
  → HttpServer writes response  # status line, headers, then fixed or chunked body
  → loop or close socket        # based on Connection header
```

- **Virtual threads** — each accepted socket runs on a virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`). Java 21 required.
- **Zero-allocation router** — `FastPathRouterImpl` uses `fpr-core`, a byte-level FSM that matches on `METHOD + path` bytes with no per-request allocation.
- **Keep-alive** — `RequestParser` reuses its header buffer across requests on the same connection.
- **Chunked transfer** — both chunked request bodies (decoded via `ChunkedInputStream`) and chunked response bodies are supported.

## Build & test

```bash
# Build all modules (skip tests)
mvn clean package -DskipTests

# Run all tests
mvn test

# Run a single test class
mvn test -pl flash -Dtest=RequestParserTest

# Run the benchmark demo server
java -jar flash-bench/target/flash-bench-1.0-SNAPSHOT.jar
```
