package dev.relism.flash;

import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.tls.TlsConfig;
import dev.relism.flash.transport.TransportLimits;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * How a {@link Server} listens: ports, TLS, limits, timeouts and protocol switches. Nothing in here
 * is about the application; that is the {@link App}.
 *
 * <pre>{@code
 * Server.start(app, ServerConfig.builder().port(8080).build());
 *
 * // Two listeners, one plain and one TLS
 * ServerConfig.builder()
 *         .listener(new ServerConfig.Listener(80))
 *         .listener(new ServerConfig.Listener(443, TlsConfig.keystore(Path.of("cert.p12"), "changeit")))
 *         .build();
 * }</pre>
 */
@Value
@Builder
public class ServerConfig {

  int port;
  String host;

  /**
   * TLS for the single default listener ({@link #port}/{@link #host}). Ignored if {@link
   * #listeners} is non-empty.
   */
  TlsConfig tls;

  /**
   * One or more listeners for this app. Non-empty list takes precedence over {@link #port}/{@link
   * #host}/{@link #tls}.
   */
  @Singular List<Listener> listeners;

  /**
   * Development mode: HTML error pages with stack traces, and a request or response touched after
   * its handler returned fails loudly. Defaults to {@code -Dflash.env=dev} or {@code FLASH_ENV=dev}.
   */
  @Builder.Default boolean dev = "dev".equalsIgnoreCase(
      System.getProperty("flash.env", System.getenv().getOrDefault("FLASH_ENV", "")));

  /**
   * Largest request body accepted, in bytes, over both protocols. A declared length above it is
   * refused with 413 before a byte of the body is read; a chunked body is cut off when it passes
   * it. Default: 10 MiB.
   */
  @Builder.Default long maxBodySize = 10L * 1024 * 1024;

  /**
   * Largest request body, in bytes, that HTTP/1.1 discards for the connection to stay open when the
   * handler left it unread. A larger one, or a chunked one, closes the connection instead: a client
   * sends the whole body before it reads the answer, so it is not kept sending an upload the
   * handler refused. Raise it for clients that report a closed connection as a network error
   * instead of reading the answer, such as the Windows WebDAV client. Default: 2 MiB.
   */
  @Builder.Default long maxDiscardedBodySize = 2L * 1024 * 1024;

  /**
   * Addresses of the proxies in front of this server. Only a connection from one of them has its
   * {@code X-Forwarded-For} believed by {@code Request#clientAddress()}; from anyone else the
   * header is whatever the client chose to send.
   */
  @Singular Set<String> trustedProxies;

  /** Maximum size of the request header buffer in bytes. Default: 64 KB. */
  @Builder.Default int maxHeaderBufferSize = 64 * 1024;

  /** Per-connection WebSocket read buffer size in bytes. Default: 64 KB. */
  @Builder.Default int wsFrameBufferSize = 64 * 1024;

  /**
   * Maximum time, in milliseconds, allowed for a request's headers to be fully read once the first
   * byte of it has arrived. Bounds the classic slowloris attack: a peer that trickles one header
   * byte every few seconds forever. Enforced by an absolute deadline (see {@code
   * dev.relism.flash.transport.BufferedByteSource}), not merely a per-read socket timeout — a
   * per-read timeout alone never trips as long as each individual read succeeds within the window,
   * no matter how long the overall header block takes. Default: 10 000
   */
  @Builder.Default int headerReadTimeoutMs = 10_000;

  /**
   * Maximum time, in milliseconds, a keep-alive connection may sit idle waiting for its next
   * request before being closed. More generous than {@link #headerReadTimeoutMs} because an idle
   * keep-alive connection is normal, expected behaviour, not an attack in progress — the tighter
   * bound applies only once bytes have actually started arriving. Default: 60 000
   */
  @Builder.Default int idleKeepAliveTimeoutMs = 60_000;

  /**
   * Maximum time, in milliseconds, a request's body may take to be fully read (by the handler or by
   * the automatic drain after it returns) once headers are parsed. Default: 30 000
   */
  @Builder.Default int bodyReadTimeoutMs = 30_000;

  /**
   * Maximum time, in milliseconds, {@link Server#stop()} waits for in-flight
   * requests to finish after it stops accepting new connections, before force-closing them.
   */
  @Builder.Default int shutdownDrainTimeoutMs = 15_000;

  /**
   * Maximum connections admitted across all listeners before new connections are closed
   * immediately at accept time, before any per-connection state (TLS handshake, protocol
   * negotiation, HPACK tables, buffers) is set up. Defaults to an auto-scaled budget based on the
   * JVM's max heap ({@link TransportLimits#defaultMaxConnections()}),
   * so a connection flood cannot exhaust the heap out of the box. Set explicitly if you know your
   * deployment's real capacity, or to {@code 0} to disable the check entirely (unlimited).
   */
  @Builder.Default int maxConnections =
      TransportLimits.defaultMaxConnections();

  /** Whether TLS listeners advertise HTTP/2 through ALPN. */
  @Builder.Default boolean http2Enabled = false;

  /**
   * Whether plaintext listeners accept the HTTP/2 prior-knowledge preface. This is independent
   * from TLS HTTP/2 and deliberately disabled by default.
   */
  @Builder.Default boolean http2CleartextEnabled = false;

  /**
   * Whether runtime-generated HTTP/2 header values use HPACK Huffman coding. Constants are always
   * compressed once at startup; leaving this disabled avoids a per-byte encode pass on responses.
   */
  @Builder.Default boolean h2HuffmanDynamicValues = false;

  /** Maximum peer RST_STREAM frames per rolling interval. */
  @Builder.Default int h2MaxResetStreamsPerInterval =
      Http2Limits.MAX_RESET_STREAMS_PER_INTERVAL;

  /** Maximum peer-created streams per rolling interval. */
  @Builder.Default int h2MaxStreamsCreatedPerInterval =
      Http2Limits.MAX_STREAMS_CREATED_PER_INTERVAL;

  /** Rolling interval used by HTTP/2 abuse-rate counters. */
  @Builder.Default long h2AbuseRateIntervalMs =
      Http2Limits.RESET_RATE_INTERVAL_MS;

  /** Maximum total streams served by one HTTP/2 connection; zero disables the budget. */
  @Builder.Default long h2MaxStreamsPerConnection =
      Http2Limits.MAX_STREAMS_PER_CONNECTION;

  /** Maximum wire bytes read by one HTTP/2 connection; zero disables the budget. */
  @Builder.Default long h2MaxBytesPerConnection =
      Http2Limits.MAX_BYTES_PER_CONNECTION;

  /** Maximum HTTP/2 connection lifetime in milliseconds; zero disables the budget. */
  @Builder.Default long h2MaxConnectionLifetimeMs =
      Http2Limits.MAX_CONNECTION_LIFETIME_MS;

  /** Maximum inactivity time for an open HTTP/2 stream. */
  @Builder.Default long h2StreamIdleTimeoutMs =
      Http2Limits.STREAM_IDLE_TIMEOUT_MS;

  /**
   * Whether every response includes a {@code Date} header (RFC 9110 §6.6.1). Default {@code true};
   * set {@code false} if Flash sits behind a reverse proxy that already adds one, to skip the
   * (already cheap — see {@code dev.relism.flash.http.DateHeader}) write.
   */
  @Builder.Default boolean sendDate = true;

  /**
   * One bind target: a TCP port, an optional bind host (default: all interfaces), and optional TLS.
   */
  public record Listener(int port, String host, TlsConfig tls) {
    public Listener(int port) {
      this(port, null, null);
    }

    public Listener(int port, TlsConfig tls) {
      this(port, null, tls);
    }
  }
}
