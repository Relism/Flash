package dev.relism.flash.ext.mcp;

import dev.relism.flash.ext.oidc.Authenticated;
import dev.relism.flash.ext.oidc.ClaimsHolder;
import dev.relism.flash.ext.oidc.OidcMiddleware;
import dev.relism.flash.ext.oidc.OidcUser;
import dev.relism.flash.ext.oidc.RolesAllowed;
import dev.relism.flash.ext.oidc.ScopesAllowed;
import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.models.Request;
import dev.relism.flash.routing.Middleware;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lazy, isolated bridge to {@code flash-ext-oidc}.
 *
 * <p>References to OIDC types only ever resolve when {@link #resolve}/{@link #compileToolPolicy}
 * are actually invoked — never at {@link McpExtension} class-load time — because they live in
 * this separate nested class. The caller wraps the invocation in {@code catch
 * (NoClassDefFoundError)}, exactly like {@code OidcExtension}'s own lazy bridge to {@code
 * flash-ext-openapi}. This is what lets {@code flash-ext-mcp} run standalone (MCP-only, no
 * OAuth2) when {@code flash-ext-oidc} is not even on the classpath. {@link Resolved}/{@link
 * McpAuthPolicy} carry only oidc-free types back out ({@link Middleware}, {@link String}, a
 * {@link Function}, a {@link Supplier}) so no other class in this package ever has to reference
 * an OIDC type.
 *
 * <p>Zero-config by design: when {@code flash-ext-oidc} is installed, everything an MCP OAuth2
 * resource server needs — issuer, canonical resource identifier, RFC 8707 audience binding, and
 * a spec-compliant {@code WWW-Authenticate} challenge (RFC 9728 §5.1) — is derived straight from
 * the installed {@link OidcMiddleware}, with no additional {@link McpConfig} calls.
 * {@link McpConfig#resourceIdentifier(String)}/{@link McpConfig#authorizationServerIssuer(String)}
 * remain as explicit overrides for the rare case where that guess is wrong.
 */
@Slf4j
final class McpOidcIntegration {

    private static final String[] NO_VALUES = new String[0];

    private McpOidcIntegration() {}

    /** Everything {@link McpExtension} needs once oidc security is resolved. */
    record Resolved(Middleware security, String issuer, Function<Request, String> resourceIdentifier) {}

    /** Returns the resolved security bundle, or {@code null} if oidc is not installed. */
    static Resolved resolve(FlashContext ctx, McpConfig config) {
        Optional<OidcMiddleware> oidc = ctx.find(OidcMiddleware.class);
        if (oidc.isEmpty()) return null;

        OidcMiddleware oidcMw = oidc.get();
        String resourceMetadataPath = "/.well-known/oauth-protected-resource" + config.rootPath();
        String issuer = config.authorizationServerIssuer() != null
                ? config.authorizationServerIssuer() : oidcMw.issuer();
        Function<Request, String> resourceId = req -> config.resourceIdentifier() != null
                ? config.resourceIdentifier()
                : OidcMiddleware.selfOrigin(req, oidcMw.selfScheme()) + config.rootPath();

        Middleware protect = oidcMw.protect(resourceMetadataPath);
        Middleware secured = Middleware.of(protect, audienceGuard(resourceId));
        return new Resolved(secured, issuer, resourceId);
    }

    /**
     * RFC 8707 audience binding, unconditionally enforced once oidc is protecting the MCP
     * route — no longer opt-in behind an explicit {@code resourceIdentifier(...)} call.
     */
    private static Middleware audienceGuard(Function<Request, String> resourceIdentifier) {
        return next -> (req, res) -> {
            Map<String, Object> claims = ClaimsHolder.get();
            String expected = resourceIdentifier.apply(req);
            if (claims != null && !audienceMatches(claims.get("aud"), expected)) {
                log.warn("[flash-ext-mcp] Rejecting token (RFC 8707): aud={} does not include expected " +
                        "resource identifier \"{}\" — the authorization server must include this exact " +
                        "value in the access token's aud claim (e.g. an Audience protocol mapper in " +
                        "Keycloak) for this MCP server to accept it.", claims.get("aud"), expected);
                throw HttpException.forbidden();
            }
            return next.handle(req, res);
        };
    }

    private static boolean audienceMatches(Object aud, String expected) {
        if (aud instanceof String s) return s.equals(expected);
        if (aud instanceof Iterable<?> it) {
            for (Object o : it) if (expected.equals(String.valueOf(o))) return true;
        }
        return false;
    }

    /**
     * Compiles {@code @RolesAllowed}/{@code @ScopesAllowed} on a tool class into a {@link
     * McpAuthPolicy}, or returns {@code null} if the tool carries none of the three OIDC
     * annotations. Called once per tool at boot ({@link McpRegistry#scan}), never on the
     * request hot path — the {@link Supplier} it returns is what runs per {@code tools/call},
     * closing over the already-normalized role/scope arrays so the hot path itself allocates
     * nothing beyond what {@link OidcUser#hasRole}/{@link OidcUser#hasScope} already do.
     *
     * <p>Fails fast at boot, not silently at request time, for the two ways this can be
     * misconfigured: the annotation present without OAuth2 actually protecting this MCP server
     * ({@code oidcActive == false}), and {@code @Authenticated} — which has no per-tool meaning
     * here (see below) — used at all.
     */
    static McpAuthPolicy compileToolPolicy(Class<? extends McpTool> toolClass, boolean oidcActive,
                                            String rolesClaimPath) {
        Authenticated auth = toolClass.getAnnotation(Authenticated.class);
        RolesAllowed roles = toolClass.getAnnotation(RolesAllowed.class);
        ScopesAllowed scopes = toolClass.getAnnotation(ScopesAllowed.class);
        if (auth == null && roles == null && scopes == null) return null;

        if (!oidcActive) {
            throw new IllegalStateException(
                    "MCP tool \"" + toolClass.getSimpleName() + "\" declares @Authenticated/@RolesAllowed/" +
                    "@ScopesAllowed, but this MCP server has no active OAuth2 protection — flash-ext-oidc " +
                    "is not installed for it, or McpSecurity is NONE. These annotations require " +
                    "McpSecurity.AUTO/REQUIRED with an OidcExtension installed; install one, or remove the " +
                    "annotation from " + toolClass.getSimpleName() + ".");
        }
        if (auth != null) {
            throw new IllegalStateException(
                    "MCP tool \"" + toolClass.getSimpleName() + "\" is annotated @Authenticated, which has " +
                    "no effect on an McpTool: the whole MCP endpoint is already all-or-nothing " +
                    "authenticated once oidc is active (McpSecurity.AUTO/REQUIRED) — unlike a RequestHandler " +
                    "route, there is no per-tool public/authenticated split to opt into. Remove it, or use " +
                    "@RolesAllowed/@ScopesAllowed to narrow further.");
        }

        String[] requiredRoles = roles != null ? normalizeRequired("RolesAllowed", roles.value()) : NO_VALUES;
        String[] requiredScopes = scopes != null ? normalizeRequired("ScopesAllowed", scopes.value()) : NO_VALUES;
        ScopesAllowed.Match scopeMatch = scopes != null ? scopes.match() : ScopesAllowed.Match.ALL;

        Supplier<String> check = () -> {
            OidcUser user = ClaimsHolder.user();
            if (user == null) return "not authenticated";
            if (requiredRoles.length > 0 && !hasAnyRole(user, rolesClaimPath, requiredRoles))
                return "missing required role (any of: " + String.join(", ", requiredRoles) + ")";
            if (requiredScopes.length > 0 && !hasScopes(user, requiredScopes, scopeMatch))
                return "missing required scope (" + scopeMatch + " of: " + String.join(", ", requiredScopes) + ")";
            return null;
        };
        return new McpAuthPolicy(check);
    }

    private static boolean hasAnyRole(OidcUser user, String claimPath, String[] roles) {
        for (String role : roles) if (user.hasRole(claimPath, role)) return true;
        return false;
    }

    private static boolean hasScopes(OidcUser user, String[] scopes, ScopesAllowed.Match match) {
        if (match == ScopesAllowed.Match.ALL) {
            for (String scope : scopes) if (!user.hasScope(scope)) return false;
            return true;
        }
        for (String scope : scopes) if (user.hasScope(scope)) return true;
        return false;
    }

    /** Mirrors {@code OidcAuthPolicy}'s own normalization — trim, dedupe, require non-blank. */
    private static String[] normalizeRequired(String annotationName, String[] values) {
        if (values == null || values.length == 0)
            throw new IllegalStateException("@" + annotationName + " requires at least one value");

        LinkedHashSet<String> normalized = new LinkedHashSet<>(values.length);
        for (String raw : values) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) normalized.add(trimmed);
        }
        if (normalized.isEmpty())
            throw new IllegalStateException("@" + annotationName + " requires at least one non-empty value");

        return normalized.toArray(String[]::new);
    }
}
