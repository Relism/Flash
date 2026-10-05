# Exchange interceptors

`ExchangeInterceptor` is the narrow seam for infrastructure that must observe an HTTP exchange
without changing application handlers. It is deliberately outside the application error boundary:
the wrapped exchange includes middleware, the route handler and `app.onError`, so its returned
response already carries the final application status.

```java
app.add(ExchangeInterceptor.class, (route, exchange) -> (request, response) -> {
    long started = System.nanoTime();
    Object answer = exchange.handle(request, response);
    observe(route, answer instanceof Response returned ? returned : response,
            System.nanoTime() - started);
    return answer;
});
```

Interceptors are services and are composed once, when the routes are compiled. The first service
added is the outermost. `wrapFallback` optionally covers requests that match no route, including
the framework's 404 and 405 handling. WebSocket handshakes are ordinary compiled routes, identified
by `Route.websocket()`; an interceptor decides whether that protocol belongs in its semantics.

This API describes application exchange completion, not transport completion: streaming bodies may
still be in flight after the handler returns. It is suitable for HTTP metrics, tracing, access logs
and auditing that use the selected route and final application response. Transport byte counts and
write completion belong at the protocol writer instead.

When no interceptor service is installed, Flash retains the ordinary compiled handler directly:
there is no wrapper, branch or lookup on the request path.
