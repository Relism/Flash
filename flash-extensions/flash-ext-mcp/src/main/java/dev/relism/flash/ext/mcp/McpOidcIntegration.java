package dev.relism.flash.ext.mcp;

import dev.relism.flash.ext.oidc.ClaimsHolder;
import dev.relism.flash.ext.oidc.OidcMiddleware;
import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.routing.Middleware;

import java.util.Map;
import java.util.Optional;

/**
 * Lazy, isolated bridge to {@code flash-ext-oidc}.
 *
 * <p>References to OIDC types only ever resolve when {@link #resolve} is actually invoked —
 * never at {@link McpExtension} class-load time — because they live in this separate nested
 * class. The caller wraps the invocation in {@code catch (NoClassDefFoundError)}, exactly like
 * {@code OidcExtension}'s own lazy bridge to {@code flash-ext-openapi}. This is what lets
 * {@code flash-ext-mcp} run standalone (MCP-only, no OAuth2) when {@code flash-ext-oidc} is not
 * even on the classpath.
 */
final class McpOidcIntegration {

    private McpOidcIntegration() {}

    /** Returns the security {@link Middleware} to apply, or {@code null} if oidc is not installed. */
    static Middleware resolve(FlashContext ctx, McpConfig config) {
        Optional<OidcMiddleware> oidc = ctx.find(OidcMiddleware.class);
        if (oidc.isEmpty()) return null;

        Middleware protect = oidc.get().protect();
        String resourceId = config.resourceIdentifier();
        if (resourceId == null || resourceId.isBlank()) return protect;

        return Middleware.of(protect, audienceGuard(resourceId));
    }

    /** RFC 8707 audience binding: rejects tokens whose {@code aud} claim doesn't include ours. */
    private static Middleware audienceGuard(String resourceIdentifier) {
        return next -> (req, res) -> {
            Map<String, Object> claims = ClaimsHolder.get();
            if (claims != null && !audienceMatches(claims.get("aud"), resourceIdentifier)) {
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
}
