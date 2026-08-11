package dev.relism.flash.ext.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable configuration for {@link McpExtension}.
 *
 * <pre>{@code
 * McpConfig.builder("my-mcp-server")
 *     .version("1.0.0")
 *     .rootPath("/mcp")
 *     .toolsPackage("com.example.tools")
 *     .security(McpSecurity.REQUIRED)
 *     .resourceIdentifier("https://mcp.example.com/mcp")
 *     .build();
 * }</pre>
 */
public final class McpConfig {

    private final String name;
    private final String version;
    private final String instructions;
    private final String rootPath;
    private final String toolsPackage;
    private final McpSecurity security;
    private final String resourceIdentifier;
    private final String authorizationServerIssuer;
    private final List<String> allowedOrigins;

    private McpConfig(Builder b) {
        this.name = b.name;
        this.version = b.version;
        this.instructions = b.instructions;
        this.rootPath = b.rootPath;
        this.toolsPackage = b.toolsPackage;
        this.security = b.security;
        this.resourceIdentifier = b.resourceIdentifier;
        this.authorizationServerIssuer = b.authorizationServerIssuer;
        this.allowedOrigins = List.copyOf(b.allowedOrigins);
    }

    String name()                     { return name; }
    String version()                  { return version; }
    String instructions()             { return instructions; }
    String rootPath()                 { return rootPath; }
    String toolsPackage()             { return toolsPackage; }
    McpSecurity security()            { return security; }
    String resourceIdentifier()       { return resourceIdentifier; }
    String authorizationServerIssuer() { return authorizationServerIssuer; }
    List<String> allowedOrigins()     { return allowedOrigins; }

    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private String version = "1.0.0";
        private String instructions;
        private String rootPath = "/mcp";
        private String toolsPackage;
        private McpSecurity security = McpSecurity.AUTO;
        private String resourceIdentifier;
        private String authorizationServerIssuer;
        private final List<String> allowedOrigins = new ArrayList<>();

        private Builder(String name) {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("McpConfig server name cannot be blank");
            this.name = name;
        }

        /** Server version reported in {@code initialize}'s {@code serverInfo}. Default {@code "1.0.0"}. */
        public Builder version(String version) { this.version = version; return this; }

        /** Free-text instructions surfaced to the client at {@code initialize} time. */
        public Builder instructions(String instructions) { this.instructions = instructions; return this; }

        /** HTTP path for the Streamable HTTP endpoint. Default {@code "/mcp"}. */
        public Builder rootPath(String rootPath) { this.rootPath = normalize(rootPath); return this; }

        /** Package scanned for {@link Tool @Tool}/{@link Resource @Resource}/{@link Prompt @Prompt} classes. Required. */
        public Builder toolsPackage(String toolsPackage) { this.toolsPackage = toolsPackage; return this; }

        /** OAuth2 requirement policy. Default {@link McpSecurity#AUTO}. */
        public Builder security(McpSecurity security) { this.security = security; return this; }

        /**
         * Resource identifier used for RFC 8707 audience binding: tokens whose {@code aud} claim
         * does not include this value are rejected. Optional — if unset, only standard bearer
         * validation (signature/issuer/expiry) is enforced, not audience binding.
         */
        public Builder resourceIdentifier(String resourceIdentifier) { this.resourceIdentifier = resourceIdentifier; return this; }

        /**
         * Authorization server issuer URL, used to publish an RFC 9728 Protected Resource
         * Metadata document at {@code /.well-known/oauth-protected-resource{rootPath}} so MCP
         * clients can discover it automatically. Requires {@link #resourceIdentifier(String)}
         * to also be set. Optional — without it, bearer validation still works, clients just
         * need the authorization server configured out-of-band.
         */
        public Builder authorizationServerIssuer(String issuer) { this.authorizationServerIssuer = issuer; return this; }

        /**
         * Origins allowed to call the MCP endpoint (DNS-rebinding protection, per the Streamable
         * HTTP transport spec). If never set, {@code Origin} validation is skipped and a warning
         * is logged at boot.
         */
        public Builder allowedOrigins(String... origins) { this.allowedOrigins.addAll(List.of(origins)); return this; }

        public McpConfig build() {
            if (toolsPackage == null || toolsPackage.isBlank())
                throw new IllegalStateException(
                        "McpConfig.toolsPackage(...) is required — declare at least one @Tool/@Resource/@Prompt class");
            return new McpConfig(this);
        }

        private static String normalize(String path) {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("rootPath cannot be blank");
            return path.startsWith("/") ? path : "/" + path;
        }
    }
}
