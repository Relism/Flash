package dev.relism.flash.ext.mcp;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.ext.security.AuthenticationEntryPoint;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.ext.security.SecurityScheme;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.Middleware;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MCP (Model Context Protocol) server extension. Streamable HTTP transport — a single {@code POST}
 * JSON-RPC endpoint, stateless in this revision (see {@code docs/transport.md}) — dispatching to
 * {@link Tool @Tool}/{@link Resource @Resource}/{@link Prompt @Prompt} classes under
 * {@link McpConfig#toolsPackage(String)}.
 *
 * <pre>{@code
 * app.install(new SecurityExtension())
 *    .install(new OidcExtension(OidcProvider.of("sso", issuer, clientId, secret)))
 *    .install(new McpExtension(McpConfig.builder("my-server").toolsPackage("com.example.tools").build()));
 * }</pre>
 *
 * <p>Every call is authenticated by the application's security chain — OAuth2 bearer tokens, API
 * keys, anything registered. When an OAuth2 issuer is among its schemes, the endpoint is also an
 * OAuth2 protected resource: RFC 9728 metadata, a {@code resource_metadata} challenge, and RFC 8707
 * audience binding for audience-bound tokens. Tool annotations are enforced per call, with
 * {@code @RolesAllowed(on = ...)} reading tool arguments.
 */
@Slf4j
public class McpExtension implements FlashExtension {

    private final McpConfig config;
    private volatile List<SecurityScheme> schemes;

    public McpExtension(McpConfig config) {
        this.config = config;
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.onReady(() -> {
            SecurityExtension security = config.security() == McpSecurity.NONE ? null : ctx.find(SecurityExtension.class)
                    .orElseThrow(() -> new IllegalStateException("MCP server \"" + config.name()
                            + "\" requires flash-ext-security-core: install a SecurityExtension, or set McpSecurity.NONE for a public server"));
            McpDispatcher dispatcher = new McpDispatcher(McpRegistry.scan(config.toolsPackage(), ctx, security),
                    config.name(), config.version(), config.instructions());

            List<Middleware> chain = new ArrayList<>(List.of(
                    McpTransportGuards.httpExceptionGuard(), McpTransportGuards.originGuard(config.allowedOrigins())));
            if (security != null) protect(app, security, chain);
            chain.addAll(config.middleware());
            app.post(config.rootPath(), (req, res) -> {
                dispatcher.handle(req, res);
                return null;
            }, chain.toArray(Middleware[]::new));
        });
    }

    private void protect(FlashRegistrar<?> app, SecurityExtension security, List<Middleware> chain) {
        String metadataPath = "/.well-known/oauth-protected-resource" + config.rootPath();
        List<AuthenticationMechanism> only = config.mechanisms();
        AuthenticationEntryPoint anonymous = (req, res) -> {
            List<SecurityScheme> schemes = schemes(security);
            res.header("WWW-Authenticate", issuers(schemes, null).isEmpty()
                    ? String.join(", ", schemes.stream().map(SecurityScheme::challenge).toList())
                    : "Bearer resource_metadata=\"" + security.origin(req) + metadataPath + "\"");
            throw HttpException.unauthorized();
        };
        chain.add(only.isEmpty() ? security.enforce(SecurityPolicy.AUTHENTICATED, anonymous)
                : security.enforce(SecurityPolicy.AUTHENTICATED, anonymous, only));
        if (config.requireTokenAudience()) {
            chain.add(next -> (req, res) -> {
                String resource = security.origin(req) + config.rootPath();
                if (!SecurityIdentity.current().principal().hasAudience(resource)) {
                    log.warn("[flash-ext-mcp] Rejected a token not issued for {} (RFC 8707) — the authorization server must put it in aud", resource);
                    throw HttpException.forbidden();
                }
                return next.handle(req, res);
            });
        } else {
            log.warn("[flash-ext-mcp] Token audience validation (RFC 8707) is DISABLED for {} — every token a registered issuer signs is accepted.", config.rootPath());
        }
        app.get(metadataPath, (req, res) -> {
            List<String> issuers = issuers(schemes(security), security.origin(req));
            if (issuers.isEmpty()) throw HttpException.notFound("Protected resource metadata");
            res.type(ContentType.JSON);
            return McpResourceMetadata.build(security.origin(req) + config.rootPath(), issuers, config.scopesSupported());
        });
    }

    /** Resolved at the first request, once every mechanism has registered — which may be after this extension was ready. */
    private List<SecurityScheme> schemes(SecurityExtension security) {
        if (schemes == null) {
            schemes = config.mechanisms().isEmpty() ? security.schemes()
                    : config.mechanisms().stream().flatMap(mechanism -> mechanism.schemes().stream()).toList();
        }
        return schemes;
    }

    /** {@code "/"} is the application's own authorization server, at {@code origin}. */
    private static List<String> issuers(List<SecurityScheme> schemes, String origin) {
        return schemes.stream().map(SecurityScheme::issuer).filter(Objects::nonNull).map(issuer -> issuer.equals("/") ? origin : issuer).toList();
    }
}
