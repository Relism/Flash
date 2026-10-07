# Flash core

Flash is an HTTP/1.1 and HTTP/2 server library for Java 21, built on blocking I/O and virtual threads. This directory documents the `flash` module. Extensions document themselves in
`flash-extensions/<name>/docs`.

## Application model


| Page                           | Topic                                                 |
| ------------------------------ | ----------------------------------------------------- |
| [app.md](app.md)               | `App`, `Module`, `Server`, boot sequence              |
| [services.md](services.md)     | Registering and injecting services                    |
| [routing.md](routing.md)       | Routes, paths, annotations, scan, mounts, route hooks |
| [handlers.md](handlers.md)     | `RequestHandler`, `DuplexHandler`, return values      |
| [middleware.md](middleware.md) | Middleware, ordering, interceptors, CORS              |
| [errors.md](errors.md)         | `HttpException`, error handlers                       |




## Messages


| Page                               | Topic                                                          |
| ---------------------------------- | -------------------------------------------------------------- |
| [request.md](request.md)           | Reading a request: headers, parameters, body, forms, multipart |
| [response.md](response.md)         | Building a response: status, headers, bodies, trailers         |
| [streaming.md](streaming.md)       | Streamed bodies and `Duplex`                                   |
| [static-files.md](static-files.md) | Serving files from the classpath                               |
| [websocket.md](websocket.md)       | WebSocket handlers                                             |
| [sse.md](sse.md)                   | Server-sent events                                             |




## Server


| Page                               | Topic                                                |
| ---------------------------------- | ---------------------------------------------------- |
| [config.md](config.md)             | Every `ServerConfig` option                          |
| [tls.md](tls.md)                   | Keystores, SNI, client certificates, ALPN            |
| [transport.md](transport.md)       | Listeners, connections, protocol selection, shutdown |
| [http1.md](http1.md)               | HTTP/1.1 parsing rules, limits, framing              |
| [http2/README.md](http2/README.md) | HTTP/2                                               |
| [design.md](design.md)             | Allocation and threading model                       |




## Minimal server

```java
App app = App.create()
        .get("/hello/{name}", (req, res) -> "Hello " + req.param("name"));

Server.start(app, ServerConfig.builder().port(8080).build()).await();
```

