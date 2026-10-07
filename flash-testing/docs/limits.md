# Limits

What the harness does not do, and why.

| Limit | Reason | Alternative |
|---|---|---|
| No TLS | The harness serves plaintext on loopback and builds its client for `http://127.0.0.1`. `tls(...)` and `listener(...)` in `config` fail the start | Start the app with `Server.start` and your own client |
| No HTTP/2 | The JDK client reaches cleartext HTTP/2 through an `Upgrade: h2c` handshake. Flash supports cleartext HTTP/2 by prior knowledge only | Raw sockets that write frames, as core's `http2` tests do |
| No WebSocket over HTTP/2 | `java.net.http.WebSocket` does not use extended CONNECT | Hand-built frames |
| No malformed requests | The JDK client cannot emit an invalid request line, a bad header block or a chunked request body | Raw sockets |
| Only string bodies | Responses are read as `String`. Binary bodies are not exposed | Use `client()` with a different body handler |
| No graceful shutdown tests | The drain window is fixed at 250 ms | Build the server directly |

`client()` returns the `HttpClient`, so anything it supports can be done by hand against `baseUri()`.
