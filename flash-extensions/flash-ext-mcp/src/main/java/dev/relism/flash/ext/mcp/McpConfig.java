package dev.relism.flash.ext.mcp;

import dev.relism.flash.routing.Middleware;

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
 *     .scopesSupported("openid", "profile", "email")
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
    private final boolean requireTokenAudience;
    private final List<String> allowedOrigins;
    private final List<String> scopesSupported;
    private final List<Middleware> middleware;

    private McpConfig(Builder b) {
        this.name = b.name;
        this.version = b.version;
        this.instructions = b.instructions;
        this.rootPath = b.rootPath;
        this.toolsPackage = b.toolsPackage;
        this.security = b.security;
        this.requireTokenAudience = b.requireTokenAudience;
        this.allowedOrigins = List.copyOf(b.allowedOrigins);
        this.scopesSupported = List.copyOf(b.scopesSupported);
        this.middleware = List.copyOf(b.middleware);
    }

    String name()                     { return name; }
    String version()                  { return version; }
    String instructions()             { return instructions; }
    String rootPath()                 { return rootPath; }
    String toolsPackage()             { return toolsPackage; }
    McpSecurity security()            { return security; }
    boolean requireTokenAudience()    { return requireTokenAudience; }
    List<String> allowedOrigins()     { return allowedOrigins; }
    List<String> scopesSupported()    { return scopesSupported; }
    List<Middleware> middleware()     { return middleware; }

    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private String version = "1.0.0";
        private String instructions;
        private String rootPath = "/mcp";
        private String toolsPackage;
        private McpSecurity security = McpSecurity.REQUIRED;
        private boolean requireTokenAudience = true;
        private final List<String> allowedOrigins = new ArrayList<>();
        private final List<String> scopesSupported = new ArrayList<>();
        private final List<Middleware> middleware = new ArrayList<>();

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

        /** Default {@link McpSecurity#REQUIRED}. */
        public Builder security(McpSecurity security) { this.security = security; return this; }

        /**
         * Whether a bearer token must name this endpoint in its {@code aud} (RFC 8707), as the MCP
         * authorization spec requires. Default {@code true}. Turn it off for an authorization server
         * that cannot mint a resource audience — every token a registered issuer signs is then
         * accepted on the endpoint, and a warning is logged at boot.
         */
        public Builder requireTokenAudience(boolean require) { this.requireTokenAudience = require; return this; }

        /**
         * Origins allowed to call the MCP endpoint (DNS-rebinding protection, per the Streamable
         * HTTP transport spec). If never set, {@code Origin} validation is skipped and a warning
         * is logged at boot.
         */
        public Builder allowedOrigins(String... origins) { this.allowedOrigins.addAll(List.of(origins)); return this; }

        /** Published as {@code scopes_supported} in the RFC 9728 metadata, so OAuth clients request them. */
        public Builder scopesSupported(String... scopes) { this.scopesSupported.addAll(List.of(scopes)); return this; }

        /** Runs on the MCP route after the transport guards and authentication — rate limiting, auditing, tracing. */
        public Builder middleware(Middleware... middleware) {
            this.middleware.addAll(List.of(middleware));
            return this;
        }

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
