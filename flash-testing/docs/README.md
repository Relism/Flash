# flash-testing

JUnit 5 harness for testing Flash applications. Boots a real app on an OS-assigned port,
swaps services for fakes, and asserts on responses. No mocking library, no assertion library —
`flash` and `junit-jupiter-api`, nothing else.

## What it provides

| Component | Description |
|---|---|
| `FlashTest` | JUnit 5 extension — owns one app's lifecycle and hands you a client |
| `FlashRequest` | Header/body builder; the HTTP verb is terminal and sends |
| `FlashResponse` | Chainable assertions that report the real response on failure |
| `FlashWebSocket` | WebSocket client over `java.net.http`, with a queue and timeouts |

## Dependency

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-testing</artifactId>
    <version>${flash.version}</version>
    <scope>test</scope>
</dependency>
```

## Quick start

```java
class UserRoutesTest {

    @RegisterExtension
    static FlashTest app = FlashTest.of(new BlogApp())
            .mock(UserService.class, new InMemoryUserService());

    @Test
    void listsUsers() {
        app.get("/api/users")
           .expectStatus(200)
           .expectHeader("content-type", "application/json")
           .expectBodyContains("alice");
    }
}
```

## The application under test

`FlashTest.of` takes a `FlashApplication` — your app's routes, extensions and services declared
independently of the port they run on:

```java
public final class BlogApp implements FlashApplication {
    @Override public void configure(FlashApp app) {
        app.install(new JacksonExtension());
        app.mount("/api", scope -> scope.scan("dev.blog.api"));
        app.ws("/live", new FeedSocket());
    }
}

FlashApp.create(8080).apply(new BlogApp()).startAndBlock();   // production
```

It takes `FlashApp` rather than `FlashRegistrar` deliberately: `ws()` and `mount()` live there,
and an application that could not register a WebSocket route or a mounted namespace would be a
half-application.

It is a `@FunctionalInterface`, so a lambda and a named class are the same thing:

```java
FlashTest.of(app -> app.get("/ping", (req, res) -> "pong"))
```

## Requests

`get` and `delete` skip the builder when there is nothing to add. Everything else goes through
`request()`, where the HTTP verb sends:

```java
app.get("/api/users").expectStatus(200);

app.request()
   .header("Authorization", "Bearer " + token)
   .json("{\"name\":\"bob\"}")
   .post("/api/users")
   .expectStatus(201);
```

`json(...)` sets the body and `content-type: application/json`. `send(method, path)` reaches any
verb, including the ones Flash adds beyond RFC 9110 (`PURGE`, `QUERY`).

Assertions chain, and every failure message carries the request line, status and body:

```
expected: <200> but was: <404>
  request: GET /api/users
  status:  404
  body:    {"status":404,"error":"Not Found"}
```

`status()`, `body()`, `headers()` and `header(name)` are there for anything the assertions do
not cover.

## Replacing services

`mock` installs its replacements as the **last** extension, after everything the application and
its own extensions declare, so a fake always wins:

```java
FlashTest.of(new BlogApp())
        .mock(UserService.class, new InMemoryUserService())
        .mock(Clock.class, Clock.fixed(instant, ZoneOffset.UTC));
```

Any object is accepted — a hand-written fake, or a Mockito mock you created yourself. This module
depends on no mocking library.

Prefer a constructor parameter when the application already takes one:
`new AdminApi(upstreamUri)` needs no override at all. `mock` is for what you do not control —
services declared inside `configure`, or provided by an installed extension.

Calling `mock` after the server has started throws.

## More than one server

`FlashTest` is an ordinary object in a field, so a class can hold as many as it needs and wire one
from another in plain Java. Booting is lazy — reading `baseUri()` starts that server on the spot —
so declaration order does the wiring, with no `@Order` and no reliance on JUnit's extension
ordering:

```java
@RegisterExtension static FlashTest auth = FlashTest.of(new FakeOidcApp());
@RegisterExtension static FlashTest api  = FlashTest.of(new BlogApp(auth.baseUri()));
```

Because binding happens when the app is created rather than when it starts, an application can
even be configured against its own address:

```java
FlashTest.of(app -> app.install(new McpExtension(
        McpConfig.builder("srv").resourceIdentifier("http://127.0.0.1:" + app.port() + "/mcp").build())));
```

A server nothing touches is never booted.

## Scope

A `static` field boots once for the test class; a non-static field boots a fresh app for every
test. That is stock JUnit field semantics — the isolation switch is the keyword, not an option:

```java
@RegisterExtension static FlashTest shared = FlashTest.of(new BlogApp());   // one boot per class
@RegisterExtension        FlashTest fresh  = FlashTest.of(new BlogApp());   // one boot per test
```

A class-scoped server shares state across its tests, including anything a `mock` fake accumulates.
Reset it in `@BeforeEach`, or use an instance field.

## WebSockets

```java
try (FlashWebSocket socket = app.ws("/live")) {
    socket.sendText("hello");
    assertEquals("echo:hello", socket.awaitText(Duration.ofSeconds(2)));
}
```

Incoming text is queued as it arrives, so a message that lands before `awaitText` is called is not
lost. `awaitClose(timeout)` returns the server's close status code. `close()` never throws — a
connection the server already dropped must not mask the failure the test was reporting.

## Configuration

`profile` customises the `FlashConfiguration` — timeouts, HTTP/2 switches, buffer sizes:

```java
FlashTest.of(new BlogApp()).profile(cfg -> cfg.http2CleartextEnabled(true));
```

Host, port and the shutdown drain window are stamped **after** the profile runs, so it cannot
break the harness. `listener(...)` and `tls(...)` are rejected — see [limits](limits.md).

## Teardown

The client is cancelled before the server stops. `HttpClient` holds keep-alive sockets open and
`ServerLifecycle.stop()` spins until the last one closes or the drain window expires, so the
default 15s drain would otherwise be paid on every test class. The harness pins it to 250ms and
uses `shutdownNow()` rather than `close()`, which blocks until every operation completes and would
hang on a leaked WebSocket.

Service cleanup registered with `FlashContext.onClose` runs after the drain, so a pooled
`DataSource` is released between test classes rather than at JVM exit.

## See also

- [limits.md](limits.md) — what the harness deliberately cannot do, and what to use instead
