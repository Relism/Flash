package dev.relism.flash.ext.mcp;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.security.AuthenticationEntryPoint;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.ext.security.SecurityScheme;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.routing.Middleware;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An MCP (Model Context Protocol) server. Streamable HTTP transport — a single {@code POST}
 * JSON-RPC endpoint, stateless in this revision (see {@code docs/transport.md}) — dispatching to
 * {@link Tool @Tool}/{@link Resource @Resource}/{@link Prompt @Prompt} classes under
 * {@link McpConfig#toolsPackage(String)}, built once at boot with their services.
 *
 * <pre>{@code
 * app.install(new SecurityModule(),
 *             new OidcModule(OidcProvider.of("sso", issuer, clientId, secret)),
 *             new McpModule(McpConfig.builder("my-server").toolsPackage("com.example.tools").build()));
 * }</pre>
 *
 * <p>Every call is authenticated by the application's security chain — OAuth2 bearer tokens, API
 * keys, anything the app adds. When an OAuth2 issuer is among its schemes, the endpoint is also an
 * OAuth2 protected resource: RFC 9728 metadata, a {@code resource_metadata} challenge, and RFC 8707
 * audience binding for audience-bound tokens. Tool annotations are enforced per call, with
 * {@code @RolesAllowed(on = ...)} reading tool arguments.
 */
@Slf4j
public class McpModule implements Module {

    private final McpConfig config;

    public McpModule(McpConfig config) {
        this.config = config;
    }

    @Override
    public void install(App app) {
        Endpoint endpoint = new Endpoint(config);
        List<Middleware> chain = new ArrayList<>(List.of(McpTransportGuards.originGuard(config.allowedOrigins())));
        boolean secured = config.security() != McpSecurity.NONE;
        // Composed when the routes compile: the app's Security is only known once it starts.
        if (secured) {
            chain.add((exchange, next) -> endpoint.authentication.handle(exchange, next));
            if (config.requireTokenAudience()) chain.add(endpoint::checkAudience);
        }
        chain.addAll(config.middleware());
        app.add(Endpoint.class, endpoint).post(config.rootPath(), endpoint, chain.toArray(Middleware[]::new));
        if (secured) app.get(endpoint.metadataPath, endpoint::metadata);
    }

    /** The JSON-RPC endpoint, built at start from the app's services. */
    private static final class Endpoint extends RequestHandler {

        private final McpConfig config;
        private final String metadataPath;
        private Security security;
        private McpDispatcher dispatcher;
        private Middleware authentication;
        private volatile List<SecurityScheme> schemes;

        Endpoint(McpConfig config) {
            this.config = config;
            this.metadataPath = "/.well-known/oauth-protected-resource" + config.rootPath();
        }

        @Override
        protected void onInit() {
            security = config.security() == McpSecurity.NONE ? null : services().find(Security.class)
                    .orElseThrow(() -> new IllegalStateException("MCP server \"" + config.name()
                            + "\" requires flash-ext-security-core: install a SecurityModule, or set McpSecurity.NONE for a public server"));
            dispatcher = new McpDispatcher(McpRegistry.scan(config.toolsPackage(), services(), security),
                    config.name(), config.version(), config.instructions());
            if (security != null) {
                List<AuthenticationMechanism> only = config.mechanisms();
                AuthenticationEntryPoint anonymous = (req, res) -> {
                    List<SecurityScheme> schemes = schemes();
                    HttpException refused = HttpException.unauthorized();
                    throw refused.header("WWW-Authenticate", issuers(schemes, null).isEmpty()
                            ? String.join(", ", schemes.stream().map(SecurityScheme::challenge).toList())
                            : "Bearer resource_metadata=\"" + security.origin(req) + metadataPath + "\"");
                };
                authentication = only.isEmpty() ? security.enforce(SecurityPolicy.AUTHENTICATED, anonymous)
                        : security.enforce(SecurityPolicy.AUTHENTICATED, anonymous, only);
            }
        }

        @Override
        protected Object handle(Request req, Response res) throws Exception {
            dispatcher.handle(req, res);
            return null;
        }

        private void checkAudience(dev.relism.flash.http.Exchange exchange,
                                   dev.relism.flash.routing.Next next) throws Exception {
            String resource = security.origin(exchange.request()) + config.rootPath();
            if (!SecurityIdentity.current().principal().hasAudience(resource)) {
                log.warn("[flash-ext-mcp] Rejected a token not issued for {} (RFC 8707) — the authorization server must put it in aud", resource);
                throw HttpException.forbidden();
            }
            next.handle(exchange);
        }

        Object metadata(Request req, Response res) {
            List<String> issuers = issuers(schemes(), security.origin(req));
            if (issuers.isEmpty()) throw HttpException.notFound("Protected resource metadata");
            res.type(ContentType.JSON);
            return McpResourceMetadata.build(security.origin(req) + config.rootPath(), issuers, config.scopesSupported());
        }

        private List<SecurityScheme> schemes() {
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
}
