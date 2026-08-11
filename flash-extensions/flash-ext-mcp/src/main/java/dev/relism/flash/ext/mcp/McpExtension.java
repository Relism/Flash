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
 * // With flash-ext-oidc as the OAuth2 resource server
 * FlashApp.create(8080)
 *     .install(new OidcExtension(oidcConfig))
 *     .install(new McpExtension(McpConfig.builder("my-mcp-server")
 *         .toolsPackage("com.example.tools")
 *         .security(McpSecurity.REQUIRED)
 *         .resourceIdentifier("https://mcp.example.com/mcp")
 *         .authorizationServerIssuer("https://auth.example.com/realms/myrealm")
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

    /**
     * Everything — scanning, binding, security resolution, route registration — happens here
     * rather than in {@link #provide}, because binding a tool calls its {@code onInit()}, which
     * may call {@code require()} on services other extensions registered lazily via
     * {@code ctx.supply()}. Per {@link FlashExtension}'s contract, {@code require()} is only
     * safe once {@code routes()} runs, after every extension's {@code provide()} phase has
     * completed and {@code FlashContext.resolveAll()} has run.
     */
    @Override
    public void routes(FlashRegistrar<?> app, FlashContext ctx) {
        McpRegistry registry = McpRegistry.scan(config.toolsPackage(), ctx);
        McpDispatcher dispatcher = new McpDispatcher(registry, config.name(), config.version(), config.instructions());

        List<Middleware> chain = new ArrayList<>(3);
        chain.add(McpTransportGuards.httpExceptionGuard());
        chain.add(McpTransportGuards.originGuard(config.allowedOrigins()));

        Middleware security = resolveSecurity(ctx);
        if (security != null) chain.add(security);

        app.post(config.rootPath(), (req, res) -> { dispatcher.handle(req, res); return null; },
                chain.toArray(Middleware[]::new));

        registerResourceMetadata(app, security != null);
    }

    private Middleware resolveSecurity(FlashContext ctx) {
        if (config.security() == McpSecurity.NONE) return null;

        Middleware oidcSecurity;
        try {
            oidcSecurity = McpOidcIntegration.resolve(ctx, config);
        } catch (NoClassDefFoundError e) {
            oidcSecurity = null; // flash-ext-oidc not on the classpath at all
        }
        if (oidcSecurity != null) return oidcSecurity;

        if (config.security() == McpSecurity.REQUIRED) {
            throw new IllegalStateException(
                    "McpSecurity.REQUIRED but flash-ext-oidc is not installed for MCP server \"" + config.name() +
                    "\" — install an OidcExtension before this McpExtension, or relax security to " +
                    "McpSecurity.AUTO/NONE if this server is meant to be public.");
        }

        log.warn("[flash-ext-mcp] MCP server \"{}\" is running WITHOUT OAuth2 protection — " +
                "flash-ext-oidc is not installed and McpSecurity.AUTO degrades to unprotected. " +
                "Install flash-ext-oidc or set McpSecurity.REQUIRED to make this a hard failure instead.",
                config.name());
        return null;
    }

    private void registerResourceMetadata(FlashRegistrar<?> app, boolean secured) {
        if (!secured) return;
        String resourceId = config.resourceIdentifier();
        String issuer = config.authorizationServerIssuer();
        if (resourceId == null || resourceId.isBlank() || issuer == null || issuer.isBlank()) return;

        String body = McpResourceMetadata.build(resourceId, issuer);
        String path = "/.well-known/oauth-protected-resource" + config.rootPath();
        app.get(path, (req, res) -> {
            res.type(ContentType.JSON);
            return body;
        });
    }
}
