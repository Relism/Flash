# TLS

TLS is configured per listener with `TlsConfig`.

```java
TlsConfig tls = TlsConfig.keystore(Path.of("server.p12"), "changeit");
ServerConfig config = ServerConfig.builder().port(8443).tls(tls).http2Enabled(true).build();
```

## From a keystore

`TlsConfig.keystore(path, password)` loads a PKCS12 or JKS keystore (JKS when the file name ends in
`.jks`, PKCS12 otherwise). The key password must equal the store password. Flash enables only
TLSv1.3 and TLSv1.2 and leaves the cipher suites to the JDK defaults.

A keystore with several key entries gets SNI selection. Each entry is indexed by its DNS
subject alternative names, or by its common name when it has none. The client's requested name picks
the entry; with no SNI, or a name that matches nothing, the first key entry in the store is served.
No hostname mapping is configured.

## From an SSLContext

`TlsConfig.ofContext(sslContext)` uses the context exactly as built. Flash applies nothing to it
except what you call on the returned `TlsConfig`: `clientAuth` and `applicationProtocols`. The
protocol list is then whatever the context allows.

## Client certificates

```java
tls.clientAuth(ClientAuth.REQUIRE);
```

| Value | Behavior |
|---|---|
| `NONE` | No certificate requested. The default |
| `OPTIONAL` | A certificate is requested; the handshake succeeds without one |
| `REQUIRE` | The handshake fails unless the client presents a trusted certificate |

Read the peer certificates in a handler with `req.sslSession().getPeerCertificates()`.

## ALPN

`applicationProtocols("acme-tls/1", "http/1.1")` sets the protocol list the listener negotiates, in
preference order. `ServerConfig.http2Enabled(true)` rewrites each TLS listener's list to put `h2` and
`http/1.1` last, keeping other entries you set. A client that negotiates `h2` is served HTTP/2; any
other result is served as HTTP/1.1.

When `h2` is offered, cipher suites on the RFC 9113 TLS 1.2 blocklist are removed from the listener.
TLS 1.3 suites are not affected.

## Handshake

The handshake runs on the connection's thread before any HTTP processing, under
[`headerReadTimeoutMs`](config.md#timeouts). A handshake failure closes the connection.

## HTTP/2 and certificates

For HTTP/2 requests on a TLS connection, Flash checks that the request's `:authority` is covered by
the certificate the connection uses (DNS and IP subject alternative names, with single-label `*.`
wildcards). A request for another host is answered `421 Misdirected Request`. A certificate without
subject alternative names, or a cleartext connection, is not checked.
