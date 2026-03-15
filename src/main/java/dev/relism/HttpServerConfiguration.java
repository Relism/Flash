package dev.relism;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HttpServerConfiguration {
    private int port;
    private String host;
}
