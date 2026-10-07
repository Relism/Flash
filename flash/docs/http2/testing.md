# Testing HTTP/2

Tests live in `flash/src/test/java/dev/relism/flash/http2`.

## Always on

| Area | Tests |
|---|---|
| Connection | handshake, SETTINGS, PING, GOAWAY, WINDOW_UPDATE, half-close, abuse limits |
| Streams | concurrency, backpressure, trailers, CONNECT, misdirected request, authority |
| Frames, HPACK, messages | tests in the `frame`, `hpack`, `message` and `stream` packages |
| WebSocket | `WebSocketOverH2Test`, `WebSocketParityTest` |
| Regression | `Http2RegressionCorpusTest` replays every wire capture in `src/test/resources/http2/regressions/` and asserts the final frame and error code |

Fuzz tests use fixed seeds, fixed maximum input lengths and a time budget, and check retained heap
after a GC.

## External tools

These run only when the tool's path is given as a system property:

| Property | Test | Tool |
|---|---|---|
| `h2spec.executable` | `H2SpecComplianceTest` | h2spec against TLS and cleartext listeners |
| `curl.executable` | `CurlInteropTest` | curl |
| `nghttp.executable` | `NghttpInteropTest` | nghttp |
| `grpcurl.executable` | `GrpcInteropTest` | grpcurl |
| `h2load.executable` and `nghttpd.executable` | `H2LoadMeasurementTest` | load comparison |
| `flash.http2.soak=true` | `Http2SoakTest` | long mixed traffic, 600 s unless `flash.http2.soak.seconds` is set |

```
mvn -pl flash test -Dh2spec.executable=/usr/local/bin/h2spec
```

`H2SpecComplianceTest` requires h2spec 2.6.0, parses the JUnit XML it writes and fails on any
failure, error or skipped case. On the cleartext port it runs the `generic`, `hpack`, `http2/3.5/1`
and `http2/4` to `http2/8` groups, which leaves out `http2/3.5/2`: that case sends a complete invalid
preface and expects a dedicated HTTP/2 endpoint, while a mixed port treats those bytes as HTTP/1.1.
