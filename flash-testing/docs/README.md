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
    static FlashTest app = FlashTest.of(Main::app)
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

## The application under test

`FlashTest.of` takes a `Supplier<App>`: the method production calls to build its app, before it
hands that app to `Server.start`. The test runs the same routes, modules and services, built by
the same code:

```java
public final class Main {
    public static App app() {
        return App.create()
                .install(new JsonModule())
                .mount("/api", api -> api.scan("dev.blog.api"))
                .ws("/live", new FeedSocket());
    }

    public static void main(String[] args) {
        Server.start(app(), ServerConfig.builder().port(8080).build()).await();   // production
    }
}
```

A lambda works as well: `FlashTest.of(() -> App.create().get("/ping", (req, res) -> "pong"))`.

`services()` returns the app's services as built, to assert on real state instead of rebuilding
it.

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

`replace` swaps every instance of a type the app adds, its modules' included, for the one given:

```java
FlashTest.of(Main::app)
        .replace(UserService.class, new InMemoryUserService())
        .replace(Clock.class, Clock.fixed(instant, ZoneOffset.UTC));
```

Any object is accepted — a hand-written fake, or a Mockito mock you created yourself. This module
depends on no mocking library.

Prefer a parameter when the application already takes one: `Main.api(upstreamUri)` needs no
replacement at all. `replace` is for what you do not control — services a module adds.

Calling `replace` after the server has started throws.

## More than one server

`FlashTest` is an ordinary object in a field, so a class can hold as many as it needs and wire one
from another in plain Java. Booting is lazy — reading `baseUri()` starts that server on the spot —
so declaration order does the wiring, with no `@Order` and no reliance on JUnit's extension
ordering:

```java
@RegisterExtension static FlashTest auth = FlashTest.of(FakeOidc::app);
@RegisterExtension static FlashTest api  = FlashTest.of(() -> Main.api(auth.baseUri()));
```

The app itself is built only then too, so one that reads a `@TempDir` sees it populated. A server
nothing touches is never booted.

## Scope

A `static` field boots once for the test class; a non-static field boots a fresh app for every
test. That is stock JUnit field semantics — the isolation switch is the keyword, not an option:

```java
@RegisterExtension static FlashTest shared = FlashTest.of(Main::app);   // one boot per class
@RegisterExtension        FlashTest fresh  = FlashTest.of(Main::app);   // one boot per test
```

A class-scoped server shares state across its tests, including anything a replacement accumulates.
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

`config` customises the `ServerConfig` — timeouts, HTTP/2 switches, limits, dev mode:

```java
FlashTest.of(Main::app).config(cfg -> cfg.http2CleartextEnabled(true));
```

Host, port and the shutdown drain window are stamped **after** it runs, so it cannot
break the harness. `listener(...)` and `tls(...)` are rejected — see [limits](limits.md).

## Teardown

The client is cancelled before the server stops. `HttpClient` holds keep-alive sockets open and
`ServerLifecycle.stop()` spins until the last one closes or the drain window expires, so the
default 15s drain would otherwise be paid on every test class. The harness pins it to 250ms and
uses `shutdownNow()` rather than `close()`, which blocks until every operation completes and would
hang on a leaked WebSocket.

The app closes its `AutoCloseable` services after the drain, so a pooled `DataSource` is released
between test classes rather than at JVM exit.

## See also

- [limits.md](limits.md) — what the harness deliberately cannot do, and what to use instead
