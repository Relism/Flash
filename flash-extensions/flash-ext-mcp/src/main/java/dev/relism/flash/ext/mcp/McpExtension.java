package dev.relism.flash.ext.mcp;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.Middleware;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP (Model Context Protocol) server extension. Streamable HTTP transport — a single
 * {@code POST} JSON-RPC endpoint, stateless in this revision (no session, no SSE stream; see
 * {@code docs/transport.md}) — dispatch precompiled at boot from classes annotated with
 * {@link Tool @Tool}/{@link Resource @Resource}/{@link Prompt @Prompt} under
 * {@link McpConfig#toolsPackage(String)}.
 *
 * <pre>{@code
 * // Standalone, no OAuth2
 * FlashApp.create(8080)
 *     .install(new McpExtension(McpConfig.builder("my-mcp-server")
 *         .toolsPackage("com.example.tools")
 *         .build()))
 *     .start();
 *
 * // With flash-ext-auth-oidc as the OAuth2 resource server — zero extra config: issuer, canonical
 * // resource identifier, RFC 8707 audience binding and RFC 9728 metadata are all derived from
 * // the installed OidcExtension.
 * FlashApp.create(8080)
 *     .install(new OidcExtension(oidcConfig))
 *     .install(new McpExtension(McpConfig.builder("my-mcp-server")
 *         .toolsPackage("com.example.tools")
 *         .security(McpSecurity.REQUIRED)
 *         .build()))
 *     .start();
 * }</pre>
 *
 * <p>One server per {@code McpExtension} instance — install multiple instances (distinct
 * {@code rootPath}, distinct {@code toolsPackage}) for multiple MCP servers on one app,
 * mirroring the {@code OidcExtension} multi-tenant pattern. See {@code docs/security.md} for
 * the full OAuth2 resolution rules.
 */
@Slf4j
public class McpExtension implements FlashExtension {

    private final McpConfig config;

    public McpExtension(McpConfig config) {
        this.config = config;
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.onReady(() -> registerRoutes(app, ctx));
    }

    private void registerRoutes(FlashRegistrar<?> app, FlashContext ctx) {
        // Resolved before scanning so McpRegistry knows, per tool, whether @RolesAllowed/
        // @ScopesAllowed are backed by real OAuth2 protection or a boot-time misconfiguration
        // (see McpOidcIntegration#compileToolPolicy) — must run first, not after.
        McpOidcIntegration.Resolved secured = resolveSecurity(ctx);
        McpRegistry registry = McpRegistry.scan(config.toolsPackage(), ctx, secured != null,
                secured == null ? null : secured.rolesClaimPath());
        McpDispatcher dispatcher = new McpDispatcher(registry, config.name(), config.version(), config.instructions());

        List<Middleware> chain = new ArrayList<>(3 + config.middleware().size());
        chain.add(McpTransportGuards.httpExceptionGuard());
        chain.add(McpTransportGuards.originGuard(config.allowedOrigins()));
        if (secured != null) chain.add(secured.security());
        chain.addAll(config.middleware());

        app.post(config.rootPath(), (req, res) -> { dispatcher.handle(req, res); return null; },
                chain.toArray(Middleware[]::new));

        registerResourceMetadata(app, secured);
    }

    private McpOidcIntegration.Resolved resolveSecurity(FlashContext ctx) {
        if (config.security() == McpSecurity.NONE) return null;

        McpOidcIntegration.Resolved resolved;
        try {
            resolved = McpOidcIntegration.resolve(ctx, config);
        } catch (NoClassDefFoundError e) {
            resolved = null; // flash-ext-auth-oidc not on the classpath at all
        }
        if (resolved != null) return resolved;

        if (config.security() == McpSecurity.REQUIRED) {
            throw new IllegalStateException(
                    "McpSecurity.REQUIRED but flash-ext-auth-oidc is not installed for MCP server \"" + config.name() +
                    "\" — install an OidcExtension before this McpExtension, or relax security to " +
                    "McpSecurity.AUTO/NONE if this server is meant to be public.");
        }

        log.warn("[flash-ext-mcp] MCP server \"{}\" is running WITHOUT OAuth2 protection — " +
                "flash-ext-auth-oidc is not installed and McpSecurity.AUTO degrades to unprotected. " +
                "Install flash-ext-auth-oidc or set McpSecurity.REQUIRED to make this a hard failure instead.",
                config.name());
        return null;
    }

    /**
     * RFC 9728 Protected Resource Metadata, built once security is resolved — no longer
     * conditioned on {@code resourceIdentifier}/{@code authorizationServerIssuer} being set
     * explicitly, since {@link McpOidcIntegration#resolve} now derives both by default. The
     * {@code resource} field is computed per request (it depends on that request's own
     * forwarded/{@code Host} headers) via {@link McpOidcIntegration.Resolved#resourceIdentifier()}.
     */
    private void registerResourceMetadata(FlashRegistrar<?> app, McpOidcIntegration.Resolved secured) {
        if (secured == null) return;
        String path = "/.well-known/oauth-protected-resource" + config.rootPath();
        app.get(path, (req, res) -> {
            res.type(ContentType.JSON);
            return McpResourceMetadata.build(
                    secured.resourceIdentifier().apply(req), secured.issuer(), config.scopesSupported());
        });
    }
}
