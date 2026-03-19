# Flash

A high-performance HTTP/1.1 server library for Java 21+, built for low-latency workloads.

## Features

- **Virtual threads** — one virtual thread per connection via `Executors.newVirtualThreadPerTaskExecutor()`
- **Zero-allocation hot path** — header buffer reused across keep-alive requests; `ByteView` slices avoid copies
- **Fast routing** — `fpr-core` compiles routes into a byte-level finite automaton at first request; path params via `PathParams`
- **Two-tier router** — mounted sub-routers matched by longest prefix, then `FastPathRouterImpl` fallback
- **Middleware chain** — composable `Middleware` lambdas fused at registration time (no per-request allocation)
- **Chunked transfer** — `ChunkedInputStream` de-chunks on the fly, keeps socket positioned for pipelining
- **Request body** — `bytes()` for full materialization or `stream()` for zero-copy streaming

## Requirements

- Java 21+
- Maven 3.8+

## Build

```bash
mvn clean package -DskipTests
```

## Quick start

```java
HttpServer server = new HttpServer(
    HttpServerConfiguration.builder().port(8080).build()
);

server.get("/hello", (req, res) -> "Hello, world!");

server.register(new MyHandler()); // @Route(path="/...", method=GET)

server.start().thenRun(() -> System.out.println("Listening on :8080"));
```

## Handler styles

**Lambda:**
```java
server.get("/ping", (req, res) -> "pong");
```

**Class-based (`@Route`):**
```java
@Route(path = "/users/{id}", method = HttpMethod.GET)
public class GetUser extends RequestHandler {
    @Override
    public Object handle(Request req, Response res) {
        String id = req.pathParam("id");
        return "{\"id\":\"" + id + "\"}";
    }
}
```

**Middleware:**
```java
Middleware cors = next -> (req, res) -> {
    res.header("Access-Control-Allow-Origin", "*");
    return next.handle(req, res);
};
server.register(new GetUser(), cors);
```

## Maven dependency

```xml
<repositories>
  <repository>
    <id>relism-releases</id>
    <url>https://maven.relism.dev/releases</url>
  </repository>
</repositories>

<dependency>
  <groupId>dev.relism</groupId>
  <artifactId>flash</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```
