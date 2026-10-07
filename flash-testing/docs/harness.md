# FlashTest

`FlashTest` is a JUnit 5 extension and a handle on the running server.

```java
FlashTest api = FlashTest.of(() -> Main.api(db));
```

`of(Supplier<App>)` takes the code that builds your app. Nothing is built or started until the first
use, so an app that reads a `@TempDir` sees it populated, and several servers can be wired from each
other in declaration order:

```java
@RegisterExtension static FlashTest auth = FlashTest.of(FakeIdentityProvider::app);
@RegisterExtension static FlashTest api  = FlashTest.of(() -> Main.api(auth.baseUri()));
```

## Lifecycle

| Field | Server |
|---|---|
| `static` with `@RegisterExtension` | One for the test class, stopped after all tests |
| Instance field with `@RegisterExtension` | A fresh one for each test |

Each server listens on `127.0.0.1` with an OS-assigned port, plain HTTP.

## Configuration

Both are set before the first use. Calling them after the server started throws
`IllegalStateException`.

```java
FlashTest.of(() -> Main.api(db))
        .config(cfg -> cfg.maxBodySize(1024).headerReadTimeoutMs(500))
        .replace(Mailer.class, new FakeMailer());
```

| Method | Effect |
|---|---|
| `config(Consumer<ServerConfigBuilder>)` | Adjust the [`ServerConfig`](../../flash/docs/config.md): limits, timeouts, protocol switches. The harness then sets `host`, `port` and `shutdownDrainTimeoutMs` (250 ms) itself. A `listener(...)` or `tls(...)` setting makes the start fail |
| `replace(Class<T>, T)` | Swaps every binding of that type, including those added by modules, for the instance. Runs `App.replace` after your supplier builds the app |

## Accessors

Each starts the server if it is not running.

| Method | Returns |
|---|---|
| `baseUri()`, `port()` | Where the server listens |
| `services()` | The app's `Services`, to assert on real state |
| `client()` | The `java.net.http.HttpClient` the harness uses |
| `request()` | A `FlashRequest` builder. See [requests.md](requests.md) |
| `get(path)`, `delete(path)` | Shortcuts for a request with no headers or body |
| `ws(path)` | A [`FlashWebSocket`](websocket.md) |

## Shutdown

Stopping closes the client first, since its keep-alive sockets would otherwise hold the server for the
drain window, then stops the server and waits for it.
