package dev.relism.flash.extension;

import dev.relism.flash.tls.TlsConfig;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

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

    int    port;
    String host;

    /** TLS for the single default listener ({@link #port}/{@link #host}). Ignored if {@link #listeners} is non-empty. */
    TlsConfig tls;

    /** One or more listeners for this app. Non-empty list takes precedence over {@link #port}/{@link #host}/{@link #tls}. */
    @Singular
    List<Listener> listeners;

    /** Maximum size of the request header buffer in bytes. Default: 64 KB. */
    @Builder.Default
    int maxHeaderBufferSize = 64 * 1024;

    /** Per-connection WebSocket read buffer size in bytes. Default: 64 KB. */
    @Builder.Default
    int wsFrameBufferSize = 64 * 1024;

    /** One bind target: a TCP port, an optional bind host (default: all interfaces), and optional TLS. */
    public record Listener(int port, String host, TlsConfig tls) {
        public Listener(int port)               { this(port, null, null); }
        public Listener(int port, TlsConfig tls) { this(port, null, tls); }
    }
}
