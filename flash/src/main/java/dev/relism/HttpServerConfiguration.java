package dev.relism;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HttpServerConfiguration {
    private int port;
    private String host;
    @Builder.Default
    private int acceptorThreads = 1;
    @Builder.Default
    private int maxHeaderBufferSize = 64 * 1024;
}
