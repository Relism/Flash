# Flash

A high-performance HTTP/1.1 server library for Java 21, built around virtual threads and a zero-allocation FSM router.

## Modules

| Module | Description |
|---|---|
| `flash` | Core server library — router, request parser, HTTP I/O transport |
| `flash-extensions/flash-ext-jackson` | Jackson JSON integration |
| `flash-extensions/flash-ext-openapi` | OpenAPI 3.0 spec + Swagger UI |
| `flash-extensions/flash-ext-oidc` | OIDC Authorization Code + PKCE flow |
| `flash-extensions/flash-ext-mcp` | MCP (Model Context Protocol) server — Streamable HTTP, optional OAuth2 via flash-ext-oidc |
| `flash-extensions/flash-ext-view-core` | Minimal shared SSR runtime primitives |
| `flash-extensions/flash-ext-view-jte` | Opinionated jte SSR extension |
| `flash-extensions/flash-ext-view-thymeleaf` | Opinionated Thymeleaf SSR extension |
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

Extend `RequestHandler`, annotate it, then scan its package. Dependencies are cached in
`onInit()` after Flash has resolved its complete boot-time service graph:

```java
@GET("/api/users")
public class ListUsers extends RequestHandler {
    private UserService users;

    @Override protected void onInit() { users = require(UserService.class); }
    @Override public Object handle(Request req, Response res) { return users.list(); }
}

app.scan("dev.example.api");
```

### Middleware

Apply middleware at registration. Flash composes the final chain at boot:

```java
Middleware authCheck = next -> (req, res) -> {
    if (req.header("Authorization") == null)
        return res.status(401).body("Unauthorized");
    return next.handle(req, res);
};

app.get("/secure", (req, res) -> "secret data", authCheck);
```

Multiple middlewares are composed outermost-first (left-to-right in the call):

```java
app.get("/admin", handler, logging, auth, rateLimit);
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
    scope.scan("dev.example.api");
});
```

## Extensions

Extensions have one declarative `configure` method. They declare services, processors and route
callbacks; Flash resolves the complete graph, materialises routes, compiles both routers, then
opens listeners. Extension install order never makes a service “not ready”.

```java
FlashApp.create(8080)
    .install(new JacksonExtension())
    .install(new OpenApiExtension("/openapi", "My API", "1.0.0"))
    .install(new OidcExtension(oidcConfig))
    .scan("dev.example.handlers")
    .start();
```

See extension-specific READMEs for full details:
- [`flash-ext-jackson`](flash-extensions/flash-ext-jackson/README.md)
- [`flash-ext-openapi`](flash-extensions/flash-ext-openapi/README.md)
- [`flash-ext-oidc`](flash-extensions/flash-ext-oidc/README.md)
- [`flash-ext-mcp`](flash-extensions/flash-ext-mcp/docs/README.md)
- [`flash-ext-view-jte`](flash-extensions/flash-ext-view-jte/README.md)
- [`flash-ext-view-thymeleaf`](flash-extensions/flash-ext-view-thymeleaf/README.md)

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
| `tls` | `null` | TLS for the default listener — see [TLS](#tls) |
| `listeners` | `[]` | Multiple bind targets (port + host + optional TLS) on one app — see [TLS](#tls) |
| `maxHeaderBufferSize` | `65536` | Max size of the header buffer (bytes) |

## TLS

HTTPS and WSS are a transport-layer concern only: once a listener is bound, the accepted
`Socket` is either plain or an `SSLSocket` indistinguishably from `HttpServer`'s point of view
onward — the request parser, router, and WebSocket upgrade never branch on it. WSS is therefore
not a separate feature; it's a WebSocket upgrade running over whatever transport it was handed.

### Quick start

```java
FlashApp.create(FlashConfiguration.builder()
        .port(443)
        .tls(TlsConfig.keystore(Path.of("cert.p12"), "changeit"))
        .build())
    .get("/ping", (req, res) -> "pong")   // HTTPS
    .ws("/live", handler)                 // WSS, same route API
    .start();
```

### Multiple listeners

One app can bind any number of ports, each independently plain or TLS:

```java
FlashApp.create(FlashConfiguration.builder()
        .listener(new FlashConfiguration.Listener(80))                                   // plain
        .listener(new FlashConfiguration.Listener(443, TlsConfig.keystore(cert, pass)))  // TLS
        .build());
```

A non-empty `listeners` list takes precedence over the top-level `port`/`host`/`tls` fields.
Each listener gets its own accept threads; the router, WS router, and virtual-thread executor
are shared by all of them — one app, N ports.

### `TlsConfig`

| Factory | Use |
|---|---|
| `TlsConfig.keystore(Path, String)` | Builds the `SSLContext` from a PKCS12/JKS keystore (type guessed from the extension). Pins `TLSv1.2`/`TLSv1.3` as enabled protocols; cipher suites are left at the JDK's own curated default. |
| `TlsConfig.ofContext(SSLContext)` | Escape hatch — the given `SSLContext` is used exactly as built. Flash never calls `setSSLParameters` on this path beyond what you explicitly request via `clientAuth`/`applicationProtocols`, so anything else you configured (custom `KeyManager`, ALPN, cipher suites) is authoritative. |

Chainable on either factory:

```java
TlsConfig.keystore(cert, pass)
    .clientAuth(ClientAuth.REQUIRE)                    // mTLS: NONE (default) | OPTIONAL | REQUIRE
    .applicationProtocols("acme-tls/1", "http/1.1")    // ALPN, in preference order
```

**SNI** falls out of `keystore()` for free: a keystore holding more than one certificate entry
is matched against the requested hostname by each certificate's SAN (falling back to CN) — no
per-hostname config. The first entry in the keystore is the default when SNI is absent or
matches nothing (same convention as nginx/HAProxy's `default_server`).

**ALPN and custom certificate selection** (e.g. TLS-ALPN-01 / RFC 8737 for on-demand ACME
issuance): ALPN is resolved while consuming `ClientHello`/producing `ServerHello`, which always
precedes `Certificate` production. A custom `X509ExtendedKeyManager` passed via `ofContext`
can therefore read `engine.getHandshakeApplicationProtocol()` (or
`((SSLSocket) socket).getHandshakeApplicationProtocol()`) inside
`chooseEngineServerAlias`/`chooseServerAlias` — the negotiated protocol is already resolved by
then, so the certificate decision can key off it.

**mTLS with a private CA**: `clientAuth(...)` only requests/requires a client certificate;
`keystore()` deliberately doesn't expose a way to configure which CAs are trusted for that
certificate (it uses the JDK default trust store). For a private CA, build the `SSLContext`
yourself with a `TrustManagerFactory` and use `ofContext(...)`.

### Reading TLS info from a request

```java
app.get("/whoami", (req, res) -> {
    if (!req.isSecure()) return "plain";
    SSLSession session = req.sslSession();          // null iff !isSecure()
    X509Certificate peer = (X509Certificate) session.getPeerCertificates()[0]; // mTLS only
    return session.getCipherSuite() + " / " + session.getProtocol();
});
```

`Request.isSecure()` / `Request.sslSession()` cost nothing extra per request: the `SSLSocket`
reference is threaded through once per connection (same mechanism as `remoteAddress()`), and
`sslSession()` only calls `SSLSocket#getSession()` — a cached-field read once the handshake
that got the request this far has already completed, never a forced handshake.

`WebSocketSession` mirrors this exactly (`isSecure()`, `sslSession()`) by delegating to the
upgrading `Request` — no separate TLS state is tracked for WS.

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
- **TLS is transport-only** — see [TLS](#tls). Listeners bind either a plain `ServerSocket` or an `SSLServerSocket`; nothing downstream of `accept()` branches on which.

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
