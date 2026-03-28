package dev.relism.extension;

import lombok.Builder;
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
 * }</pre>
 */
@Value
@Builder
public class FlashConfiguration {

    int    port;
    String host;

    /** Maximum size of the request header buffer in bytes. Default: 64 KB. */
    @Builder.Default
    int maxHeaderBufferSize = 64 * 1024;
}
