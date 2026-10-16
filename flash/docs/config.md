# ServerConfig

`ServerConfig` describes how a `Server` listens. It holds nothing about the application. It is
immutable and built with a builder.

```java
ServerConfig config = ServerConfig.builder()
        .port(8080)
        .maxBodySize(1024 * 1024)
        .http2CleartextEnabled(true)
        .build();
```

`ServerConfig` is registered as a service, so a handler or service can take it as a constructor
parameter.

## Listeners

| Option | Default | Meaning |
|---|---|---|
| `port` | `0` | Port of the default listener. `0` lets the OS pick; read it with `Server.port()` |
| `host` | `null` | Bind address. `null` binds all interfaces |
| `tls` | `null` | [TLS](tls.md) for the default listener |
| `listener(...)` | none | Adds a `ServerConfig.Listener(port, host, tls)`. When any is added, `port`, `host` and `tls` are ignored |

```java
ServerConfig.builder()
        .listener(new ServerConfig.Listener(80))
        .listener(new ServerConfig.Listener(443, TlsConfig.keystore(Path.of("cert.p12"), "secret")))
        .build();
```

All listeners serve the same app.

## Development mode

| Option | Default | Meaning |
|---|---|---|
| `dev` | `true` when system property `flash.env` or environment variable `FLASH_ENV` is `dev` (case-insensitive) | Enables the checks below |

When on:

- Unhandled exceptions and 404 answers are HTML pages; the first includes the stack trace.
- A `Request` or `Response` used after its handler returned throws `IllegalStateException`.

Leave it off in production: the pages disclose internals.

## Limits

| Option | Default | Meaning |
|---|---|---|
| `maxBodySize` | 10 MiB | Largest request body, in bytes, on both protocols. See [request.md](request.md#body) |
| `maxDiscardedBodySize` | 2 MiB | Largest body HTTP/1.1 discards, when the handler left it unread, to keep the connection open. See [http1.md](http1.md#unread-bodies) |
| `maxHeaderBufferSize` | 64 KiB | Largest HTTP/1.1 request head. Above it: `431` |
| `wsFrameBufferSize` | 64 KiB | Largest WebSocket message. Longer: close code 1009 |
| `maxConnections` | `heap * 0.5 / 5 MiB`, at least 64 | Connections admitted at once across all listeners. `0` means unlimited |
| `trustedProxies` | none | Addresses of reverse proxies. Only these have their `X-Forwarded-For` honored by `Request.clientAddress()` |

`maxConnections` is checked on accept: a connection over the limit is closed immediately, before TLS
or any per-connection state exists. The check is approximate under a burst of simultaneous accepts.
The default is computed from `Runtime.maxMemory()`.

## Timeouts

All in milliseconds.

| Option | Default | Bounds |
|---|---|---|
| `headerReadTimeoutMs` | 10,000 | Time to read a full HTTP/1.1 request head once its first byte arrived. Also the TLS handshake. It is an absolute deadline, so trickling one byte at a time does not extend it |
| `idleKeepAliveTimeoutMs` | 60,000 | Time a keep-alive connection may wait for its next request. Also the wait for the cleartext HTTP/2 preface probe |
| `bodyReadTimeoutMs` | 30,000 | Time to read a request body, by the handler or by the discard after it ([http1.md](http1.md#unread-bodies)) |
| `shutdownDrainTimeoutMs` | 15,000 | How long `stop()` waits for in-flight requests before closing them |

## Protocol

| Option | Default | Meaning |
|---|---|---|
| `http2Enabled` | `false` | Adds `h2` and `http/1.1` to the ALPN list of TLS listeners |
| `http2CleartextEnabled` | `false` | Plain listeners accept the HTTP/2 prior-knowledge preface. See [http2/README.md](http2/README.md#cleartext) |
| `h2HuffmanDynamicValues` | `false` | Huffman-codes HTTP/2 response header values built at runtime. Constant values are always coded once at boot |
| `sendDate` | `true` | Adds a `Date` header to every response. Turn off when a proxy adds it |

## HTTP/2 abuse limits

| Option | Default | Meaning |
|---|---|---|
| `h2MaxResetStreamsPerInterval` | 200 | `RST_STREAM` frames per interval |
| `h2MaxStreamsCreatedPerInterval` | 100,000 | Streams a peer may open per interval |
| `h2AbuseRateIntervalMs` | 10,000 | The rolling interval. At least 2 |
| `h2MaxStreamsPerConnection` | 100,000 | Streams one connection may serve. `0` disables |
| `h2MaxBytesPerConnection` | 0 | Wire bytes one connection may read. `0` disables |
| `h2MaxConnectionLifetimeMs` | 0 | Age of one connection. `0` disables |
| `h2StreamIdleTimeoutMs` | 60,000 | Stream with no activity in either direction |

What happens when one is exceeded is in [http2/limits.md](http2/limits.md).
