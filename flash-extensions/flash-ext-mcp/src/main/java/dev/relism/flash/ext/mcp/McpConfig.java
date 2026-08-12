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
    private final String resourceIdentifier;
    private final String authorizationServerIssuer;
    private final List<String> allowedOrigins;
    private final List<String> scopesSupported;
    private final String rolesClaimPath;

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
        this.scopesSupported = List.copyOf(b.scopesSupported);
        this.rolesClaimPath = b.rolesClaimPath;
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
    List<String> scopesSupported()    { return scopesSupported; }
    String rolesClaimPath()           { return rolesClaimPath; }

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
        private final List<String> scopesSupported = new ArrayList<>();
        private String rolesClaimPath = "realm_access.roles";

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
         * Canonical URI of this MCP endpoint, used for RFC 8707 audience binding: tokens whose
         * {@code aud} claim does not include this value are rejected. Optional — when
         * {@code flash-ext-oidc} is installed, this is auto-derived per request from the
         * forwarded/{@code Host} headers (same resolution {@code OidcExtension} uses for its own
         * redirect URIs) and audience binding is enforced unconditionally. Set this explicitly
         * only to override that guess — a reverse proxy that forwards neither
         * {@code X-Forwarded-Host} nor {@code X-Forwarded-Proto}.
         */
        public Builder resourceIdentifier(String resourceIdentifier) { this.resourceIdentifier = resourceIdentifier; return this; }

        /**
         * Authorization server issuer URL, published in the RFC 9728 Protected Resource
         * Metadata document at {@code /.well-known/oauth-protected-resource{rootPath}}. Optional
         * — when {@code flash-ext-oidc} is installed, this is auto-derived from its configured
         * issuer. Set this explicitly only to override that (e.g. publishing a different issuer
         * than the one actually validating tokens).
         */
        public Builder authorizationServerIssuer(String issuer) { this.authorizationServerIssuer = issuer; return this; }

        /**
         * Origins allowed to call the MCP endpoint (DNS-rebinding protection, per the Streamable
         * HTTP transport spec). If never set, {@code Origin} validation is skipped and a warning
         * is logged at boot.
         */
        public Builder allowedOrigins(String... origins) { this.allowedOrigins.addAll(List.of(origins)); return this; }

        /**
         * OAuth2 scopes this server expects clients to request, published as {@code
         * scopes_supported} in the RFC 9728 Protected Resource Metadata document. Optional per
         * the spec — omitted from the document entirely if never set. A spec-compliant client
         * reads this to know what to put in its authorization/token requests instead of
         * requesting nothing; see {@code docs/keycloak.md}'s "same story for any other claim"
         * section for why this matters in practice (a client that requests no scope only gets
         * whatever your authorization server treats as always-included, e.g. Keycloak's `basic`).
         * Purely advertisement — this server still validates whatever token it actually receives
         * the same way regardless of what a client requested.
         */
        public Builder scopesSupported(String... scopes) { this.scopesSupported.addAll(List.of(scopes)); return this; }

        /**
         * Claim path used to resolve roles for {@code @RolesAllowed} on an {@link McpTool} —
         * same dot-path syntax and default (Keycloak's {@code realm_access.roles}) as {@code
         * OidcConfig#rolesClaimPath()}. Set this only if the two configs diverge; there is no
         * way to auto-derive it from the installed {@code OidcExtension} (see {@code
         * docs/security.md}'s {@code @RolesAllowed}/{@code @ScopesAllowed} section for why).
         */
        public Builder rolesClaimPath(String rolesClaimPath) { this.rolesClaimPath = rolesClaimPath; return this; }

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
