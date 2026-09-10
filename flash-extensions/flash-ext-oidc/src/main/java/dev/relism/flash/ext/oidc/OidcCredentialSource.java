package dev.relism.flash.ext.oidc;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.ext.auth.CredentialSource;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.Request;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The OpenID Connect {@link CredentialSource}: it turns what a request carries into claims, and
 * rejects it the way OAuth2 says to when it cannot. Authorization on those claims is
 * {@code flash-ext-auth-core}'s job, not this class's.
 *
 * <p>Resolution order on each request:
 * <ol>
 *   <li>{@code Authorization: Bearer ...} header — validated via JWKS ({@link JwtValidator}).</li>
 *   <li>{@code oidc_session} cookie — looked up in {@link OidcSessionStore}; transparently
 *       refreshed if the access token is expired.</li>
 *   <li>Browser clients (no {@code Accept: application/json}) → redirect to
 *       {@code {routePrefix}/login?redirect={path}}.</li>
 *   <li>API clients → 401 with a {@code WWW-Authenticate: Bearer} challenge.</li>
 * </ol>
 */
public final class OidcCredentialSource implements CredentialSource {

    private static final String BEARER = "Bearer";

    private final JwtValidator         validator;
    private final OidcConfig           config;
    private final OidcProviderMetadata meta;
    private final TokenClient          tokenClient;
    private final String               resourceMetadataPath;

    OidcCredentialSource(JwtValidator validator, OidcConfig config,
                         OidcProviderMetadata meta, TokenClient tokenClient) {
        this(validator, config, meta, tokenClient, null);
    }

    private OidcCredentialSource(JwtValidator validator, OidcConfig config,
                                 OidcProviderMetadata meta, TokenClient tokenClient,
                                 String resourceMetadataPath) {
        this.validator            = validator;
        this.config               = config;
        this.meta                 = meta;
        this.tokenClient          = tokenClient;
        this.resourceMetadataPath = resourceMetadataPath;
    }

    // -- CredentialSource -----------------------------------------------------

    /** OIDC issuer this source validates tokens against — the {@code iss} claim it enforces. */
    public String issuer() { return config.issuer(); }

    /** Scheme used to build this app's own absolute URLs — see {@link OidcConfig#selfScheme()}. */
    public String selfScheme() { return config.selfScheme(); }

    /**
     * A copy of this source whose 401 challenges also carry {@code resource_metadata}
     * (RFC 9728 §5.1), resolved against the request's own scheme and host exactly like
     * {@link OidcExtension}'s redirect URIs. {@code path} is absolute, e.g.
     * {@code "/.well-known/oauth-protected-resource/mcp"}.
     *
     * <p>Used by {@code flash-ext-mcp} to make its Protected Resource Metadata document
     * discoverable straight from the {@code WWW-Authenticate} header, per the MCP Authorization
     * spec.
     */
    public OidcCredentialSource withResourceMetadata(String path) {
        return new OidcCredentialSource(validator, config, meta, tokenClient, path);
    }

    @Override
    public Map<String, Object> authenticate(Request req, Response res) {
        return resolve(req, res, resourceMetadataPath);
    }

    @Override
    public Map<String, Object> peek(Request req) {
        return resolveQuiet(req);
    }

    @Override
    public String insufficientScopeChallenge(String[] requiredScopes) {
        return bearerChallenge() + ", error=\"insufficient_scope\", scope=\""
                + quoted(spaceDelimited(requiredScopes)) + "\"";
    }

    // -- Internals ------------------------------------------------------------

    /**
     * Like {@link #resolve} but never redirects or throws — returns {@code null} silently
     * when no valid credentials are present. Used by {@link #optional()}.
     */
    private Map<String, Object> resolveQuiet(Request req) {
        String bearerToken = extractBearerToken(req.header("Authorization"));
        if (bearerToken != null) {
            try {
                return validator.validate(bearerToken);
            } catch (Exception ignored) {
                return null;
            }
        }

        String sessionId = cookieValue(req, "oidc_session");
        if (sessionId != null) {
            Optional<OidcSession> found = config.sessionStore().find(sessionId);
            if (found.isPresent()) {
                OidcSession session = found.get();
                if (!session.isAccessTokenExpired())
                    return session.claims();
                if (session.refreshToken() != null) {
                    try {
                        OidcSession refreshed = doRefresh(session);
                        config.sessionStore().save(refreshed);
                        return refreshed.claims();
                    } catch (Exception ignored) { }
                }
                config.sessionStore().delete(sessionId);
            }
        }
        return null;
    }

    /**
     * Returns claims on success, or {@code null} if a redirect was already written to
     * {@code res}. Throws {@link HttpException} 401/403 for API clients.
     */
    private Map<String, Object> resolve(Request req, Response res) {
        return resolve(req, res, null);
    }

    private Map<String, Object> resolve(Request req, Response res, String resourceMetadataPath) {
        // 1. Bearer token
        String bearerToken = extractBearerToken(req.header("Authorization"));
        if (bearerToken != null) {
            try {
                return validator.validate(bearerToken);
            } catch (HttpException e) {
                res.header("WWW-Authenticate", invalidTokenChallenge(req, resourceMetadataPath));
                throw e;
            }
        }

        // 2. Session cookie
        String sessionId = cookieValue(req, "oidc_session");
        if (sessionId != null) {
            Optional<OidcSession> found = config.sessionStore().find(sessionId);
            if (found.isPresent()) {
                OidcSession session = found.get();

                if (!session.isAccessTokenExpired())
                    return session.claims();

                // Access token expired — try silent refresh
                if (session.refreshToken() != null) {
                    try {
                        OidcSession refreshed = doRefresh(session);
                        config.sessionStore().save(refreshed);
                        return refreshed.claims();
                    } catch (Exception ignored) {
                        // Refresh failed — fall through to re-authenticate
                    }
                }
                config.sessionStore().delete(sessionId);
            }
        }

        // 3. No valid credentials
        String accept = req.header("Accept");
        if (accept != null && accept.contains("application/json")) {
            res.header("WWW-Authenticate", bearerChallenge(req, resourceMetadataPath));
            throw HttpException.unauthorized();
        }

        // Browser — redirect to login, preserving the original URL in state
        String loginUrl = config.routePrefix() + "/login?redirect="
                + URLEncoder.encode(req.path(), StandardCharsets.UTF_8);
        res.redirect(loginUrl);
        return null;
    }

    private OidcSession doRefresh(OidcSession old) throws Exception {
        OidcTokenResponse tokens = tokenClient.refresh(
                meta.tokenEndpoint(), old.refreshToken());

        Map<String, Object> claims = mergeRefreshedClaims(tokens, old);

        return new OidcSession(
                old.id(),
                tokens.accessToken(),
                tokens.idToken() != null ? tokens.idToken() : old.idToken(),
                tokens.refreshToken() != null ? tokens.refreshToken() : old.refreshToken(),
                Instant.now().plusSeconds(tokens.expiresIn()),
                claims
        );
    }

    static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) return null;
        int len = authorizationHeader.length();
        int start = 0;
        while (start < len && Character.isWhitespace(authorizationHeader.charAt(start))) start++;
        int schemeEnd = start + BEARER.length();
        if (schemeEnd > len || !authorizationHeader.regionMatches(true, start, BEARER, 0, BEARER.length())) {
            return null;
        }
        if (schemeEnd == len || !Character.isWhitespace(authorizationHeader.charAt(schemeEnd))) {
            return null;
        }
        int tokenStart = schemeEnd;
        while (tokenStart < len && Character.isWhitespace(authorizationHeader.charAt(tokenStart))) tokenStart++;
        if (tokenStart >= len) return null;
        int tokenEnd = len;
        while (tokenEnd > tokenStart && Character.isWhitespace(authorizationHeader.charAt(tokenEnd - 1))) tokenEnd--;
        return tokenEnd > tokenStart ? authorizationHeader.substring(tokenStart, tokenEnd) : null;
    }

    String bearerChallenge() {
        return bearerChallenge(null, null);
    }

    private String bearerChallenge(Request req, String resourceMetadataPath) {
        String base = BEARER + " realm=\"" + quoted(config.schemeName()) + "\"";
        if (resourceMetadataPath == null) return base;
        return base + ", resource_metadata=\"" + quoted(absoluteSelf(req, resourceMetadataPath)) + "\"";
    }

    String invalidTokenChallenge() {
        return invalidTokenChallenge(null, null);
    }

    private String invalidTokenChallenge(Request req, String resourceMetadataPath) {
        return bearerChallenge(req, resourceMetadataPath) + ", error=\"invalid_token\"";
    }

    private String absoluteSelf(Request req, String path) {
        if (!path.startsWith("/")) return path;
        return selfOrigin(req, config.selfScheme()) + path;
    }

    /**
     * {@code scheme://host} clients actually reach this app on — the basis for every absolute
     * URL it publishes about itself (OAuth2 {@code redirect_uri}, the RFC 9728 resource
     * identifier and the {@code resource_metadata} challenge). Behind a reverse proxy the
     * request's own {@code Host} is the upstream address the proxy dialled, so
     * {@code X-Forwarded-Host}/{@code -Proto} win whenever present: without them the app would
     * name an address no client can resolve, and OAuth2 discovery fails with no error anyone
     * can trace back to here. Trusted unconditionally — a caller able to reach this app without
     * passing the proxy can do worse than spoof a self URL.
     */
    public static String selfOrigin(Request req, String fallbackScheme) {
        String forwardedHost = req.header("X-Forwarded-Host");
        if (forwardedHost == null) return fallbackScheme + "://" + req.header("Host");
        String forwardedProto = req.header("X-Forwarded-Proto");
        return (forwardedProto != null ? forwardedProto : fallbackScheme) + "://" + forwardedHost;
    }

    private static String spaceDelimited(String[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static Map<String, Object> mergeRefreshedClaims(OidcTokenResponse tokens, OidcSession old) {
        Map<String, Object> merged = new HashMap<>();
        // Fall back to old claims first, then overlay fresh token claims
        merged.putAll(old.claims());
        if (tokens.accessToken() != null)
            merged.putAll(JwtUtils.parseClaims(tokens.accessToken()));
        if (tokens.idToken() != null)
            merged.putAll(JwtUtils.parseClaims(tokens.idToken()));
        return Map.copyOf(merged);
    }

    // -- Shared cookie utility (also used by OidcExtension) -------------------

    static String cookieValue(Request req, String name) {
        String header = req.header("Cookie");
        if (header == null || header.isBlank()) return null;
        int len   = header.length();
        int start = 0;
        while (start < len) {
            int semi = header.indexOf(';', start);
            int end  = semi < 0 ? len : semi;
            int eq   = header.indexOf('=', start);
            if (eq > start && eq < end) {
                int ns = start, ne = eq;
                while (ns < ne && header.charAt(ns)   == ' ') ns++;
                while (ne > ns && header.charAt(ne-1) == ' ') ne--;
                if (ne - ns == name.length() && header.regionMatches(ns, name, 0, name.length()))
                    return header.substring(eq + 1, end).strip();
            }
            start = end + 1;
        }
        return null;
    }
}
