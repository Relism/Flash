package dev.relism.flash.extension;

import dev.relism.flash.tls.TlsConfig;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * Configuration for a {@link FlashApp} instance.
 *
 * <pre>{@code
 * // Minimal — port only
 * FlashApp.create(8080);
 *
 * // Full control
 * FlashApp.create(FlashConfiguration.builder()
 *         .port(8080)
 *         .host("127.0.0.1")
 *         .maxHeaderBufferSize(128 * 1024)
 *         .build());
 *
 * // TLS on the single default listener
 * FlashApp.create(FlashConfiguration.builder()
 *         .port(443)
 *         .tls(TlsConfig.keystore(Path.of("cert.p12"), "changeit"))
 *         .build());
 *
 * // Multiple listeners on one app — takes precedence over port/host/tls above
 * FlashApp.create(FlashConfiguration.builder()
 *         .listener(new FlashConfiguration.Listener(80))
 *         .listener(new FlashConfiguration.Listener(443, TlsConfig.keystore(Path.of("cert.p12"), "changeit")))
 *         .build());
 * }</pre>
 */
@Value
@Builder
public class FlashConfiguration {

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
   * Maximum time, in milliseconds, {@link dev.relism.flash.ServerHandle#stop()} waits for in-flight
   * requests to finish after it stops accepting new connections, before force-
   */
  @Builder.Default int shutdownDrainTimeoutMs = 15_000;

  /**
   * Whether this server negotiates HTTP/2. When enabled, plaintext listeners recognize h2c prior
   * knowledge and TLS listeners advertise {@code h2} followed by HTTP/1.1 through ALPN. Disabled by
   * default until the HTTP/2 request/response path is complete.
   */
  @Builder.Default boolean http2Enabled = false;

  /**
   * Whether runtime-generated HTTP/2 header values use HPACK Huffman coding. Constants are always
   * compressed once at startup; leaving this disabled avoids a per-byte encode pass on responses.
   */
  @Builder.Default boolean h2HuffmanDynamicValues = false;

  /** Maximum peer RST_STREAM frames per rolling interval. */
  @Builder.Default int h2MaxResetStreamsPerInterval =
      dev.relism.flash.http2.Http2Limits.MAX_RESET_STREAMS_PER_INTERVAL;

  /** Maximum peer-created streams per rolling interval. */
  @Builder.Default int h2MaxStreamsCreatedPerInterval =
      dev.relism.flash.http2.Http2Limits.MAX_STREAMS_CREATED_PER_INTERVAL;

  /** Rolling interval used by HTTP/2 abuse-rate counters. */
  @Builder.Default long h2AbuseRateIntervalMs =
      dev.relism.flash.http2.Http2Limits.RESET_RATE_INTERVAL_MS;

  /** Maximum total streams served by one HTTP/2 connection; zero disables the budget. */
  @Builder.Default long h2MaxStreamsPerConnection =
      dev.relism.flash.http2.Http2Limits.MAX_STREAMS_PER_CONNECTION;

  /** Maximum wire bytes read by one HTTP/2 connection; zero disables the budget. */
  @Builder.Default long h2MaxBytesPerConnection =
      dev.relism.flash.http2.Http2Limits.MAX_BYTES_PER_CONNECTION;

  /** Maximum HTTP/2 connection lifetime in milliseconds; zero disables the budget. */
  @Builder.Default long h2MaxConnectionLifetimeMs =
      dev.relism.flash.http2.Http2Limits.MAX_CONNECTION_LIFETIME_MS;

  /** Maximum inactivity time for an open HTTP/2 stream. */
  @Builder.Default long h2StreamIdleTimeoutMs =
      dev.relism.flash.http2.Http2Limits.STREAM_IDLE_TIMEOUT_MS;

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
