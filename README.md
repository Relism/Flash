# Flash

A high-performance HTTP/1.1 and HTTP/2 server library for Java 21, built around virtual threads,
a zero-allocation FSM router, bounded protocol state, and one shared request/response API.

## Modules

| Module | Description |
|---|---|
| `flash` | Core server library — HTTP/1.1 and HTTP/2 transport, router, request/response model |
| `flash-testing` | JUnit 5 harness — boot an app on an ephemeral port, fake its services, assert on responses |
| `flash-extensions/flash-ext-serde` | Handlers that name a codec and a type for each end |
| `flash-extensions/flash-ext-avaje-jsonb` | JSON bodies and responses through Avaje Jsonb, adapters generated at build time |
| `flash-extensions/flash-ext-jackson-core` | What every Jackson format shares: one mapper as a codec |
| `flash-extensions/flash-ext-jackson-json` | JSON bodies and responses |
| `flash-extensions/flash-ext-jackson-xml` | XML bodies and responses |
| `flash-extensions/flash-ext-protobuf-java` | Binary Protocol Buffers through Google's generated Java messages |
| `flash-extensions/flash-ext-validation-core` | One contract for checking a body, and one way for the engine to describe its rules |
| `flash-extensions/flash-ext-validation-jakarta` | Jakarta Validation through Hibernate Validator |
| `flash-extensions/flash-ext-validation-avaje` | Avaje Validator: the same rules without reflection, generated at build time |
| `flash-extensions/flash-ext-validation-protovalidate` | Protovalidate rules declared in protobuf descriptors |
| `flash-extensions/flash-ext-openapi` | OpenAPI 3.0 spec, read by Swagger UI, Redoc or Scalar |
| `flash-extensions/flash-ext-security-core` | Security: authentication chain, annotations, sessions, OpenAPI |
| `flash-extensions/flash-ext-security-oidc` | OpenID Connect: bearer tokens, code flow + PKCE |
| `flash-extensions/flash-ext-security-apikey` | API keys |
| `flash-extensions/flash-ext-security-form` | Password sign-in |
| `flash-extensions/flash-ext-security-oauth-server` | OAuth 2.1 authorization server for the application's own users and resources |
| `flash-extensions/flash-ext-security-test` | Test identities, fake OpenID Provider |
| `flash-extensions/flash-ext-mcp` | MCP (Model Context Protocol) server — Streamable HTTP, secured by flash-ext-security-core |
| `flash-extensions/flash-ext-view-core` | What every template engine module shares: `ViewHandler`, `ViewModel` |
| `flash-extensions/flash-ext-view-jte` | jte templates, rendered to bytes |
| `flash-extensions/flash-ext-view-thymeleaf` | Thymeleaf templates |
| `flash-extensions/flash-ext-limiter` | Rate limits: `@Limit` or middleware, lock-free counters |
| `flash-extensions/flash-ext-vite` | Vite frontend: dev server in dev, the built SPA from the jar otherwise |
| `flash-extensions/flash-ext-vite-maven-plugin` | Builds the Vite frontend into the jar during `mvn package` |
| `flash-extensions/flash-ext-scheduler` | Interval and cron background jobs on virtual threads |
| `flash-extensions/flash-ext-cache-core` | Caching contract — `Cache`, `CacheManager`, `CacheSpec` |
| `flash-extensions/flash-ext-cache-caffeine` | In-process cache backed by Caffeine |
| `flash-extensions/flash-ext-micrometer` | HTTP server metrics for any Micrometer registry |
| `flash-extensions/flash-ext-micrometer-prometheus` | Micrometer metrics with a streaming Prometheus endpoint |
| `flash-extensions/flash-ext-data-core` | Transactions: `Tx`, `@Transactional`, repositories |
| `flash-extensions/flash-ext-data-jdbc` | `TxManager` and repositories over a `DataSource` |
| `flash-extensions/flash-ext-data-hibernate` | `TxManager` and repositories over a Hibernate `SessionFactory` |

## Requirements

- Java 21+
- Maven 3.8+

## Quick start

```java
Server.start(App.create().get("/ping", (req, res) -> "pong"),
        ServerConfig.builder().port(8080).build()).await();
```

An `App` is what the server runs: routes, middleware, modules, services. It knows nothing of ports,
so a test runs the same app on a port of its own. `Server.start` builds every route, then opens
the listeners; `stop()` drains in-flight requests and closes the app's services.

## Route registration

### Lambda routes

```java
App app = App.create();

app.get("/hello", (req, res) -> "world");

app.post("/echo", (req, res) -> res.status(200).body(req.body().bytes()));

app.get("/users/{id}", (req, res) -> "user:" + req.param("id"));
```

### Class-based handlers

Implement `Handler`, annotate it, then scan its package. The app builds it through its only
constructor, whose parameters are the services it needs:

```java
@GET("/api/users")
public final class ListUsers implements Handler {
    private final UserService users;

    public ListUsers(UserService users) { this.users = users; }

    @Override public Object handle(Request req, Response res) { return users.list(); }
}

app.add(UserService.class).scan("dev.example.api");
```

`onInit()` is an optional hook that runs once at boot, for precomputation that belongs off the
hot path.

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

Scans a package, subpackages included, for handler classes carrying `@GET`, `@POST` and the
other verbs, or `@Ws`. Each is built like any other service:

```java
app.scan("dev.example.handlers");
```

### Namespace mounting

Mount a scope under a prefix. Its routes get the prefix, and its middleware wraps only them; the
services are the app's:

```java
app.mount("/api", scope -> {
    scope.get("/health", (req, res) -> "ok");          // → GET /api/health
    scope.scan("dev.example.api");
});
```

## Modules and services

A `Module` is a reusable piece of an app: an extension is a module shipped in a jar, and an
application splits itself into modules the same way.

```java
App.create()
    .install(new JsonModule(), new OpenApiModule("/openapi", "My API", "1.0.0"))
    .install(new SecurityModule(), new OidcModule(OidcProvider.of("sso", issuer, clientId, secret)))
    .scan("dev.example.handlers");
```

Services are added by type, `add(type)`, `add(type, implementation)` or `add(type, instance)`,
and built when the server starts, each through its only constructor. A parameter is `T`,
`Optional<T>`, `List<T>` (every one added under `T`, in order) or `Services`. What a module
contributes to others is a service too: `RouteHook` (middleware from a handler's annotations,
and every route as it is built), `ExchangeInterceptor` (infrastructure around a completed exchange),
`OpenApiContributor`, `AuthenticationMechanism`. Every
`AutoCloseable` service is closed when the server stops. The `ServerConfig` the server starts
with is itself a service, so a module's handler reads `isDev()` from it.

See each module's README for details:
- [`flash-ext-serde`](flash-extensions/flash-ext-serde/README.md)
- [`flash-ext-avaje-jsonb`](flash-extensions/flash-ext-avaje-jsonb/README.md)
- [`flash-ext-jackson-core`](flash-extensions/flash-ext-jackson-core/README.md)
- [`flash-ext-jackson-json`](flash-extensions/flash-ext-jackson-json/README.md)
- [`flash-ext-jackson-xml`](flash-extensions/flash-ext-jackson-xml/README.md)
- [`flash-ext-protobuf-java`](flash-extensions/flash-ext-protobuf-java/README.md)
- [`flash-ext-validation-protovalidate`](flash-extensions/flash-ext-validation-protovalidate/README.md)
- [`flash-ext-openapi`](flash-extensions/flash-ext-openapi/README.md)
- [`flash-ext-security-core`](flash-extensions/flash-ext-security-core/docs/README.md)
- [`flash-ext-security-oidc`](flash-extensions/flash-ext-security-oidc/docs/README.md)
- [`flash-ext-security-apikey`](flash-extensions/flash-ext-security-apikey/docs/README.md)
- [`flash-ext-security-form`](flash-extensions/flash-ext-security-form/docs/README.md)
- [`flash-ext-security-test`](flash-extensions/flash-ext-security-test/docs/README.md)
- [`flash-ext-mcp`](flash-extensions/flash-ext-mcp/docs/README.md)
- [`flash-ext-view-jte`](flash-extensions/flash-ext-view-jte/README.md)
- [`flash-ext-view-thymeleaf`](flash-extensions/flash-ext-view-thymeleaf/README.md)
- [`flash-ext-scheduler`](flash-extensions/flash-ext-scheduler/docs/README.md)
- [`flash-ext-cache-caffeine`](flash-extensions/flash-ext-cache-caffeine/docs/README.md)
- [`flash-ext-micrometer`](flash-extensions/flash-ext-micrometer/docs/README.md)
- [`flash-ext-micrometer-prometheus`](flash-extensions/flash-ext-micrometer-prometheus/docs/README.md)
- [`flash-ext-limiter`](flash-extensions/flash-ext-limiter/README.md)
- [`flash-ext-vite`](flash-extensions/flash-ext-vite/docs/README.md)
- [`flash-ext-data-core`](flash-extensions/flash-ext-data-core/docs/README.md)
- [`flash-testing`](flash-testing/docs/README.md)

## Error handlers

```java
app.onNotFound((req, res) -> res.status(404).body("Not found: " + req.path()));

app.onError((failure, req, res) -> {
    if (failure instanceof IllegalArgumentException)
        return res.status(400).body(failure.getMessage());
    return Errors.render(HttpException.internal("Internal error"), res);
});
```

By default an `HttpException` is answered with its status, its headers and
`{"error", "status"}`, and anything else is a logged 500 that reveals nothing; in dev both are
HTML pages with the stack trace.

## ServerConfig

| Field | Default | Description |
|---|---|---|
| `port` | — | TCP port to bind |
| `host` | `"0.0.0.0"` | Bind address |
| `tls` | `null` | TLS for the default listener — see [TLS](#tls) |
| `listeners` | `[]` | Multiple bind targets (port + host + optional TLS) on one app — see [TLS](#tls) |
| `maxHeaderBufferSize` | `65536` | Max size of the header buffer (bytes) |
| `wsFrameBufferSize` | `65536` | Per-connection WebSocket read buffer (bytes) |
| `headerReadTimeoutMs` | `10000` | Once a request's first byte arrives, how long the full header block may take. Bounds slowloris-style attacks — see [`HTTP1-HARDENING.md`](flash/docs/core/HTTP1-HARDENING.md). |
| `idleKeepAliveTimeoutMs` | `60000` | How long a keep-alive connection may sit idle waiting for its next request. |
| `bodyReadTimeoutMs` | `30000` | How long reading a request body (handler or automatic drain) may take. |
| `shutdownDrainTimeoutMs` | `15000` | How long graceful shutdown waits for in-flight requests before force-closing. |
| `maxConnections` | auto (~heap/10MB) | Maximum concurrent connections across all listeners before new ones are closed immediately at accept time, before any per-connection state (TLS handshake included) is created. Auto-scales from `Runtime.maxMemory()`; set explicitly for a known deployment size, or `0` to disable. |
| `http2Enabled` | `false` | Whether TLS listeners advertise HTTP/2 through ALPN. |
| `http2CleartextEnabled` | `false` | Whether plaintext listeners accept HTTP/2 prior knowledge (h2c). Independent from TLS HTTP/2. |
| `h2HuffmanDynamicValues` | `false` | HPACK-Huffman encode runtime response values. Constants remain pre-encoded; the measured default avoids an extra encode pass. |
| `h2MaxResetStreamsPerInterval` | `200` | Rapid Reset budget per rolling interval. |
| `h2MaxStreamsCreatedPerInterval` | `400` | New-stream budget per rolling interval. |
| `h2AbuseRateIntervalMs` | `10000` | Rolling interval for the two operator-tunable rate limits above. |
| `h2MaxStreamsPerConnection` | `100000` | Total stream budget; `0` disables it. |
| `h2MaxBytesPerConnection` | `0` | Optional total wire-byte budget; `0` disables it. |
| `h2MaxConnectionLifetimeMs` | `0` | Optional connection lifetime; `0` disables it. |
| `h2StreamIdleTimeoutMs` | `60000` | Inactive open-stream deadline. |
| `sendDate` | `true` | Add an RFC 9110 `Date` field to responses; disable when an upstream proxy supplies it. |
| `dev` | `-Dflash.env=dev` or `FLASH_ENV=dev` | Development mode: error pages with stack traces, a request kept past its handler fails loudly, and modules switch to their dev behaviour (Vite's dev server, templates compiled on the fly). |
| `maxBodySize` | `10485760` | Largest request body, over both protocols: a larger declared length is a 413 before the body is read. |
| `trustedProxies` | `[]` | Proxies whose `X-Forwarded-For` `Request.clientAddress()` believes. |

## Protocols

Routes, middleware, `Request`, `Response`, bodies, trailers, streaming and WebSockets use the same
API on HTTP/1.1 and HTTP/2. Protocol selection happens once per connection:

- On TLS listeners, enable `http2Enabled`; Flash advertises `h2` and `http/1.1` through ALPN and
  uses the protocol selected by the client. Existing HTTP/1.1 clients continue to work.
- On plaintext listeners, enable `http2CleartextEnabled` to accept the HTTP/2 prior-knowledge
  preface on the same port as HTTP/1.1. Clients that do not send that exact preface are parsed as
  HTTP/1.1.
- With both switches left at their default `false`, Flash behaves as an HTTP/1.1 server.

After enabling the appropriate switch, application routes need no protocol-specific code. TLS
still requires the normal certificate configuration shown below.

Flash deliberately does not implement HTTP/2 server push, RFC 7540 dependency-tree priority
scheduling, or the obsolete HTTP/1.1 `Upgrade: h2c` transition. Server push has no application API,
RFC 9113 deprecated the old priority scheme, and cleartext HTTP/2 uses prior knowledge instead.
See the [HTTP/2 compliance record](flash/docs/http2/COMPLIANCE.md) for exact coverage.

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
Server.start(App.create()
        .get("/ping", (req, res) -> "pong")   // HTTPS
        .ws("/live", handler),                // WSS, same route API
    ServerConfig.builder()
        .port(443)
        .tls(TlsConfig.keystore(Path.of("cert.p12"), "changeit"))
        .build());
```

### Multiple listeners

One app can bind any number of ports, each independently plain or TLS:

```java
Server.start(app, ServerConfig.builder()
        .listener(new ServerConfig.Listener(80))                                   // plain
        .listener(new ServerConfig.Listener(443, TlsConfig.keystore(cert, pass)))  // TLS
        .build());
```

A non-empty `listeners` list takes precedence over the top-level `port`/`host`/`tls` fields.
Each listener gets its own accept threads; the router and the virtual-thread executor are shared
by all of them — one app, N ports.

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
(`flash/docs/core/MESSAGE-MODEL.md` has the full design record). This is what makes a warm h1
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

In dev (`ServerConfig.dev`, `-Dflash.env=dev`) a use-after-return access throws
`IllegalStateException` immediately at the offending call site instead of silently reading the
wrong request's data — turn it on in tests and local development. In production the connection
skips the step entirely.

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

`header(name, long)` writes a number's digits straight into the response, with no `String`.

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

## Testing

`flash-testing` boots a real app on an OS-assigned port for the duration of a test, and hands you
a client pointed at it. Add it with test scope:

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-testing</artifactId>
    <version>${flash.version}</version>
    <scope>test</scope>
</dependency>
```

```java
class UserRoutesTest {

    @RegisterExtension
    static FlashTest app = FlashTest.of(Main::app)                 // the method production calls
            .replace(UserService.class, new InMemoryUserService());

    @Test
    void listsUsers() {
        app.get("/api/users")
           .expectStatus(200)
           .expectHeader("content-type", "application/json")
           .expectBodyContains("alice");
    }
}
```

`FlashTest.of` takes a `Supplier<App>`, so the test runs the app production runs; `replace` swaps
one service, `config` adjusts the `ServerConfig`, and `services()` exposes the app's services as
built. Full reference: [`flash-testing/docs`](flash-testing/docs/README.md), including the
[limits](flash-testing/docs/limits.md) the harness deliberately does not cross.

## Architecture

```
TransportFactory.create()           # binds every listener, wires the connection runner
  → AcceptLoop                      # one per listener × accept thread; hands sockets off
  → ConnectionRunner.accept()       # per-connection setup: TLS handshake, protocol negotiation
      → ProtocolNegotiator          # ALPN / h2c-preface — decides the protocol once
      ├─ Http1Connection.run()      # request parser, router, handler, h1 response writer
      └─ Http2Connection.run()      # frame demux, HPACK, stream dispatch, flow control
             → Router.dispatch()    # one FSM for every route; the same Request/Response on both protocols
```

- **Virtual threads** — each accepted socket runs on a virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`, owned by `TransportFactory`). Java 21 required.
- **Zero-allocation router** — `Router` uses `fpr-core`, a byte-level FSM that matches on `METHOD + path` bytes with no per-request allocation; WebSocket routes live in the same FSM under a method of their own.
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
```
