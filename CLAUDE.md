# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the project
mvn compile

# Package (JAR)
mvn package

# Run (entry point is dev.relism.Main)
mvn exec:java -Dexec.mainClass="dev.relism.Main"

# Clean build artifacts
mvn clean

# Full clean build
mvn clean package
```

No tests exist yet. The project resolves the `fpr-core` dependency from a private Reposilite repository at `https://maven.relism.dev/releases`.

## Architecture Overview

**Flash** is a hand-rolled, zero-allocation HTTP/1.1 server built on raw Java sockets with virtual threads (Java 21). The central design goal is extreme low latency — no intermediate String allocations on the hot path.

### Request Lifecycle

```
Socket → RequestParser → GlobalRouter → AbstractRouter impl → RequestHandler → Response bytes → Socket
```

1. **`RequestParser`** — Reads raw bytes from the socket into an 8 KB buffer and scans for method, path, headers, and body without creating intermediate String objects. All parsed values are wrapped in `ByteView` implementations (see `FastPathViews`).

2. **`GlobalRouter`** — The top-level dispatcher. Holds a sorted list of mounted sub-routers (longest namespace prefix first) and a default internal router. Dispatches each request via byte-level prefix matching (`ByteUtils.startsWith`).

3. **`FastPathRouterImpl`** — The primary router implementation, backed by the external `fpr-core` library. Builds a compiled state machine (`RouterBuilder → FastPathRouter`) for ultra-fast byte-level method+path matching. Routes are marked dirty (`router = null`) when added and lazily recompiled on first request. Thread-local `MatchResult` objects avoid per-request allocation.

4. **`RadixPathRouterImpl`** — Stub; not yet implemented.

### Key Abstractions

- **`AbstractRouter`** — Base class for all routers. Has a `namespace` (byte array for zero-alloc prefix checks) and two abstract methods: `route(Request)` and `addRoute(HttpMethod, String, RequestHandler)`.
- **`RequestHandler`** — Abstract class handlers extend. The `handle(Request, Response)` method returns either a `Response` object or any other value (serialized as the body) or `null`.
- **`SimpleHandler`** — Wraps a `FunctionalHandler` lambda for the fluent DSL (`server.get(path, handler)`).
- **`@Route` annotation** — Applied to `RequestHandler` subclasses to declare their HTTP method and path. Used by `AbstractRouter.register()`.
- **`ByteView`** (`fpr-core` interface) — Zero-copy abstraction over byte sequences. Implemented by `RequestByteView` (slice into the raw buffer), `MethodPathByteView` (virtual concatenation of method + path), `SocketByteView`, and `StringByteView` — all in `FastPathViews`.

### Routing Registration (Two Styles)

**Annotation-based (class handlers):**
```java
@Route(method = "GET", path = "/profile")
public static class ProfileHandler extends RequestHandler { ... }

apiRouter.register(new ProfileHandler()); // namespace "/api" + path "/profile" = "/api/profile"
```

**Fluent DSL (lambdas):**
```java
server.get("/hello", (req, res) -> "Hello World");
```

Paths registered via `addRoute` are prefixed with the router's namespace inside `FastPathRouterImpl.addRoute`.

### Response Writing

`HttpServer` writes HTTP/1.1 responses with pre-calculated static byte arrays (`HTTP_1_1`, `CRLF`, `CONTENT_TYPE`, `CONTENT_LENGTH`) to avoid per-response allocations. `IoUtils.writeInt` writes integer values without String conversion.

### Performance Invariants to Maintain

- No `String` creation on the routing hot path (use `ByteView` and byte arrays).
- Thread-local `MatchResult` reuse in `FastPathRouterContext` — do not allocate per request.
- `GlobalRouter.route` must stay allocation-free (sorted list iterated directly).
- `RequestParser` assumes headers fit in one 8 KB burst read.
