# Routing

All routes of an app are compiled into one byte-level matcher (`FastPathRouter`) when the server
starts. Matching runs on the method and path bytes and allocates nothing per request.

## Declaring routes

```java
app.get("/users/{id}", (req, res) -> "user " + req.param("id"));
app.post("/users", CreateUser.class);                  // built by Flash, takes services
app.route(HttpMethod.QUERY, "/search", new Search());  // any method of HttpMethod
app.any("/files/**", new Files());                     // every method, PROPFIND included
```

A route is a method, a path and a handler. The handler is a lambda `(Request, Response) -> Object`,
a `Handler` instance, or a handler class. See [handlers.md](handlers.md) for what a handler returns.

### Annotated classes

A handler class can carry its route:

```java
@GET("/users/{id}")
final class GetUser extends RequestHandler {
    private final UserService users;
    GetUser(UserService users) { this.users = users; }
    @Override protected Object handle(Request req, Response res) { return users.find(req.paramLong("id")); }
}

app.scan("com.acme.api");
```

Annotations: `@GET`, `@POST`, `@PUT`, `@PATCH`, `@DELETE`, `@HEAD`, `@OPTIONS`, `@TRACE`, `@CONNECT`,
`@PURGE`, `@QUERY`, each with the path as `value()`, plus `@Sse` and `@Ws` for protocol handlers. A
verb annotation is any annotation marked `@Verb(HttpMethod.X)` with a `String value()`. An
annotation marked `@AnyMethod` with a `String value()` declares a route for every method, as `any`
does; an extension uses it for a protocol of its own methods.

`scan(package)` visits the package and its subpackages and skips abstract classes. It fails the boot
when:

- a class carries two routing annotations,
- a class carries an annotation but is not a `Handler` (`@Ws`: not a `WebSocketHandler`; `@Sse`: not
  an `SseHandler`),
- the package yields no route at all.

Scanned routes take no inline middleware. Use `use`, `mount` or a `RouteHook`.

## Path syntax

| Segment | Matches | Example |
|---|---|---|
| literal | that text | `/users` |
| `{name}` | one segment, captured | `/users/{id}` |
| literal and `{name}` mixed | one segment with captures | `/files/{name}.{ext}` |
| `*` | exactly one segment | `/a/*/c` |
| `**` | the rest of the path | `/static/**` |

Parameter names use letters, digits and `_`. An empty segment, an empty name or a stray brace fails
the boot. The router rewrites each declared path to one leading slash and no trailing or doubled
slashes, so `users//` is `/users`.

Parameters are read with `req.param(name)`, `paramInt` and `paramLong`. See
[request.md](request.md#path-and-query-parameters). A path that ends in `/**` also names what `**`
matched: `req.param("**")` is the rest of the path after the prefix, without a leading slash, as it
was sent (not percent-decoded), and empty for the prefix itself (`/files/**` matches `/files`).

## Methods

A method is a token (RFC 9110): `HttpMethod` names the common ones, and a client may send any other,
such as WebDAV's `PROPFIND`.

| Request method | Reaches |
|---|---|
| In `HttpMethod` | A route of that method, else a route for every method on the path |
| Any other token | Only a route for every method (`any`, `@AnyMethod`). `req.method()` is `null`; `req.requestLine().getMethodName()` is the token |
| A token no route takes, in an app with no route for every method | `501 Not Implemented` |
| Not a token | `400` on HTTP/1.1, the stream reset with `PROTOCOL_ERROR` on HTTP/2 |

`any(path, handler)` is one route. A route of a single method on the same path wins over it, and its
`Route.method()` is `null`.

## Matching rules

- `HEAD` with no `HEAD` route runs the `GET` route. The body is dropped on the wire, and headers
  such as `Content-Length` are those the `GET` would send. This holds when a route for every method
  covers the path too; that route gets the `HEAD` only when the path has no `GET` route.
- A path that exists under other methods answers `405` with an `Allow` header listing them.
- A path that exists under none answers `404` through `onNotFound`.
- A `WebSocketHandler` route is stored under its own key (`WS`), so one path can serve a normal
  `GET` and a WebSocket. A request is tried as a WebSocket first when it carries
  `Upgrade: websocket` (HTTP/1.1) or an extended CONNECT with `:protocol` `websocket` (HTTP/2),
  then as an ordinary route. A route for every method also answers an upgrade request on a path
  with no WebSocket route, as the `GET` it is.
- `CONNECT` routes match the request target as an authority (`host:port`), not a path.

The 404 and 405 answers pass through the app's middleware, so a CORS preflight or an access log sees
them.

## Mounts

`mount(prefix, routes)` declares routes under a prefix. The nested `App` shares services, and its
`use(...)` middleware applies to the mount's routes in addition to the parent's.

```java
app.use(accessLog)
   .mount("/api", api -> {
       api.use(auth);
       api.get("/users", ListUsers.class);   // GET /api/users, runs accessLog then auth
   })
   .get("/healthz", (req, res) -> "ok");     // runs accessLog only
```

Middleware is read when routes compile, not when `use` is called, so the order of `use` and route
declarations does not matter. See [middleware.md](middleware.md).

## RouteHook

`RouteHook` is a service that sees every route at boot. It can contribute middleware based on the
handler class (for example, from annotations) and observe the compiled route (for example, to build
a document).

```java
app.add(RouteHook.class, SecurityHook.class);
```

| Method | Called |
|---|---|
| `middleware(Class<?> handler)` | For each route, with the handler's class. Returns `MiddlewareNode`s |
| `routed(Route route)` | Once per route after its chain is composed |

`Route` is a record of `method` (`null` for a route for every method), `path` (mount prefix
included), `handler` (class) and `middleware` (classes, outermost first). For a lambda route `handler` is the class of the wrapper Flash created,
which carries no annotations.
