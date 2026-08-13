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
| `wsFrameBufferSize` | `65536` | Per-connection WebSocket read buffer (bytes) |
| `headerReadTimeoutMs` | `10000` | Once a request's first byte arrives, how long the full header block may take. Bounds slowloris-style attacks — see [`HTTP1-HARDENING.md`](flash/docs/http2/HTTP1-HARDENING.md). |
| `idleKeepAliveTimeoutMs` | `60000` | How long a keep-alive connection may sit idle waiting for its next request. |
| `bodyReadTimeoutMs` | `30000` | How long reading a request body (handler or automatic drain) may take. |
| `shutdownDrainTimeoutMs` | `15000` | How long graceful shutdown waits for in-flight requests before force-closing. |
| `http2Enabled` | `false` | Whether TLS listeners advertise HTTP/2 through ALPN. |
| `http2CleartextEnabled` | `false` | Whether plaintext listeners accept HTTP/2 prior knowledge (h2c). Independent from TLS HTTP/2. |
| `h2MaxResetStreamsPerInterval` | `200` | Rapid Reset budget per rolling interval. |
| `h2MaxStreamsCreatedPerInterval` | `400` | New-stream budget per rolling interval. |
| `h2AbuseRateIntervalMs` | `10000` | Rolling interval for the two operator-tunable rate limits above. |
| `h2MaxStreamsPerConnection` | `100000` | Total stream budget; `0` disables it. |
| `h2MaxBytesPerConnection` | `0` | Optional total wire-byte budget; `0` disables it. |
| `h2MaxConnectionLifetimeMs` | `0` | Optional connection lifetime; `0` disables it. |
| `h2StreamIdleTimeoutMs` | `60000` | Inactive open-stream deadline. |

## WebSockets over HTTP/2

The same `ws(path, handler)` route serves WebSockets over HTTP/1.1 and HTTP/2. When HTTP/2 is
enabled, Flash advertises RFC 8441 extended CONNECT support and carries WebSocket frames inside
flow-controlled DATA frames. No alternate handler, route, or session API is required:

```java
app.ws("/live", handler);
```

HTTP/1.1 clients use the ordinary `101 Switching Protocols` upgrade. HTTP/2 clients use an
extended CONNECT and receive status `200`; Flash applies the same RFC 6455 framing, masking,
fragmentation, close, and callback behavior on both transports. Client support for negotiating
WebSockets over HTTP/2 varies, so clients without RFC 8441 support continue to use HTTP/1.1.

## TLS

HTTPS and WSS are a transport-layer concern only. Once a listener is bound, the accepted socket
is plain or TLS; the selected HTTP connection implementation then performs either the HTTP/1.1
upgrade or the HTTP/2 extended CONNECT. WSS does not require a separate route or handler API.

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

## Object lifetime

`Request` and `Response` are **pooled per connection**, not allocated per request: one instance is
created per connection and repositioned (`reset()`) over each new request/response in turn — the
same idiom Java NIO buffers use, applied to the whole request/response model
(`flash/docs/http2/MESSAGE-MODEL.md` has the full design record). This is what makes a warm h1
request/response cycle 0 B/op.

**Do not retain a `Request` or `Response` past the handler that received it.** A reference kept in
a field, a captured closure, a `CompletableFuture` continuation, or a background thread and read
*after* the handler returns will observe whatever the *next* request on that connection
repositioned the same instance to — not the request you thought you had:

```java
// WRONG — captures `req`, reads it after the handler has returned
app.get("/slow", (req, res) -> {
    CompletableFuture.runAsync(() -> log(req.header("X-Trace-Id"))); // may log the NEXT request's header
    return "ok";
});
```

Copy out whatever you need before returning or handing work off asynchronously — every accessor
that returns a `String` (`header`, `param`, `query`, `path`, …) gives you an independent heap copy
that's safe to keep as long as you like:

```java
app.get("/slow", (req, res) -> {
    String traceId = req.header("X-Trace-Id");             // copy now, safe to retain
    CompletableFuture.runAsync(() -> log(traceId));
    return "ok";
});
```

Run with `-Dflash.env=dev` and a use-after-return access throws `IllegalStateException` immediately
at the offending call site instead of silently reading the wrong request's data — turn this on in
tests and local development. It's a no-op in production beyond a single `boolean` field read.

`req.body()`/`RequestBody` follows the same rule — materialise (`.bytes()`) or fully consume
(`.stream()`) it inside the handler; don't stash the `RequestBody` itself for later.

### Reusable response headers

Use `PreEncodedHeader` for a constant header sent by many responses. It stores the name and value
once and remains valid on both HTTP versions:

```java
private static final PreEncodedHeader NO_STORE =
    new PreEncodedHeader("cache-control", "no-store");

app.get("/health", (req, res) -> res.header(NO_STORE).body("ok"));
```

`Response.header(byte[])` accepts a complete CRLF-terminated HTTP/1 field line and is therefore
HTTP/1-only; HPACK needs the name and value as separate fields. Prefer `PreEncodedHeader` for shared
application and middleware code.

### Trailers and push streaming

Request trailers become available after the body reaches EOF:

```java
byte[] payload = req.body().bytes();
String status = req.trailers().first("grpc-status");
```

For a producer-driven response, `Response.streaming` provides a blocking `ResponseStream`. Its
bounded buffer and HTTP/2 flow-control windows apply backpressure directly to the producer's
virtual thread:

```java
return res.streaming(stream -> {
    try {
        stream.write(payload, 0, payload.length);
        stream.trailer("result", "complete");
    } catch (IOException failure) {
        throw new UncheckedIOException(failure);
    }
});
```

The API renders as chunked data and trailers on HTTP/1.1, and DATA plus trailing HEADERS on
HTTP/2. Flash core supplies these transport primitives; a higher-level gRPC codec belongs in a
future `flash-ext-grpc` extension.

### HTTP/2 upstream proxy

The core includes a deliberately small, proxy-oriented HTTP/2 client and a protocol-neutral relay:

```java
Http2Client upstream = new Http2Client();
app.post("/service/{path}",
    HttpProxy.toHttp2(URI.create("http://service.internal:8080"), upstream));
```

The relay preserves the path, query, body and trailers and applies one shared hop-by-hop field
policy for HTTP/1.1 and HTTP/2. Close the client when the application stops. Cleartext upstreams
use prior knowledge; Flash never implements the obsolete `Upgrade: h2c` mechanism.

## Architecture

```
TransportFactory.create()           # binds every listener, wires the connection runner
  → AcceptLoop                      # one per listener × accept thread; hands sockets off
  → ConnectionRunner.accept()       # per-connection setup: TLS handshake, protocol negotiation
      → ProtocolNegotiator          # ALPN / h2c-preface — decides the protocol once
      ├─ Http1Connection.run()      # request parser, router, handler, h1 response writer
      └─ Http2Connection.run()      # frame demux, HPACK, stream dispatch, flow control
             → RequestHandler.handle()  # the same protocol-neutral request/response API
```

- **Virtual threads** — each accepted socket runs on a virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`, owned by `TransportFactory`). Java 21 required.
- **Zero-allocation router** — `FastPathRouterImpl` uses `fpr-core`, a byte-level FSM that matches on `METHOD + path` bytes with no per-request allocation.
- **Keep-alive** — `RequestParser` reuses its header buffer across requests on the same connection.
- **Chunked transfer** — both chunked request bodies (decoded via `ChunkedInputStream`) and chunked response bodies are supported.
- **TLS is transport-only** — see [TLS](#tls). Listeners bind either a plain `ServerSocket` or an `SSLServerSocket`; nothing downstream of `accept()` branches on which.
- **`ConnectionProtocol` seam** — HTTP/1.1 and HTTP/2 are peers behind this interface, selected once per connection by `ProtocolNegotiator`; routing and application models are shared.

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
