package dev.relism.ext.oidc;

import dev.relism.extension.ExtensionContext;
import dev.relism.extension.FlashExtension;
import dev.relism.extension.FlashRegistrar;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full OIDC Authorization Code + PKCE flow for Flash.
 *
 * <p>On {@link #install}, the extension:
 * <ol>
 *   <li>Fetches the provider discovery document — fail-fast at startup.</li>
 *   <li>Registers three routes on the {@link FlashApp}:
 *     <ul>
 *       <li>{@code GET  {prefix}/login}    — builds the authorization URL and redirects.</li>
 *       <li>{@code GET  {prefix}/callback} — exchanges the code, creates a session, redirects.</li>
 *       <li>{@code POST {prefix}/logout}   — invalidates the session, redirects to the provider's
 *           end-session endpoint (if available) or to {@link OidcConfig#postLogoutRedirectUri()}.</li>
 *     </ul>
 *   </li>
 *   <li>Provides {@link OidcMiddleware} and {@link JwtValidator} in the context.</li>
 *   <li>Registers an annotation processor for {@link Authenticated} and {@link RolesAllowed}.</li>
 * </ol>
 *
 * <pre>{@code
 * // Keycloak
 * app.install(new OidcExtension(
 *     OidcConfig.builder(
 *             "https://keycloak.example.com/realms/myrealm",
 *             "my-app", "secret", "/auth/callback")
 *         .rolesClaimPath("realm_access.roles")
 *         .build()));
 *
 * // Two providers / tenants on one server
 * app.install(new OidcExtension(tenantAConfig))
 *    .install(new OidcExtension(tenantBConfig));
 * }</pre>
 */
public class OidcExtension implements FlashExtension {

    private final OidcConfig config;

    public OidcExtension(OidcConfig config) {
        this.config = config;
    }

    @Override
    public void install(FlashRegistrar app, ExtensionContext ctx) {

        // 1. Build the shared HttpClient (optionally with TLS verification disabled)
        HttpClient http = buildHttpClient(config);

        // 2. Discover provider endpoints (blocking; fail fast at startup)
        OidcProviderMetadata meta;
        try {
            meta = DiscoveryClient.fetch(config.issuer(), http);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "OIDC discovery failed for issuer: " + config.issuer(), e);
        }

        // 3. JWKS-backed access-token validator
        JwtValidator validator = new JwtValidator(
                meta.jwksUri(), config.issuer(), config.clientId(),
                config.algorithm(), http);

        // 4. PKCE state store (per extension instance — safe for multi-tenant)
        OidcStateStore stateStore = new OidcStateStore();

        // 5. Shared token client (injected into middleware for refresh)
        TokenClient tokenClient = new TokenClient(http, config);

        // 6. Middleware (also exposed in context for manual lambda-route protection)
        OidcMiddleware oidcMw = new OidcMiddleware(validator, config, meta, tokenClient);
        ctx.provide(OidcMiddleware.class, oidcMw);
        ctx.provide(JwtValidator.class,   validator);

        String prefix = config.routePrefix();

        // ── GET {prefix}/login ────────────────────────────────────────────────
        // Builds the provider authorization URL with PKCE + state and redirects.
        // Optional query param: ?redirect={relative-url}  (default: /)
        app.get(prefix + "/login", (req, res) -> {
            String verifier  = PkceUtils.generateVerifier();
            String challenge = PkceUtils.computeChallenge(verifier);
            String state     = UUID.randomUUID().toString(); // CSRF protection
            String nonce     = UUID.randomUUID().toString(); // ID token replay protection

            String redirect = req.query("redirect");
            // Only allow relative paths — prevents open-redirect attacks
            if (redirect == null || !redirect.startsWith("/")) redirect = "/";

            stateStore.put(state, redirect, verifier, nonce);

            String authUrl = meta.authorizationEndpoint()
                    + "?response_type=code"
                    + "&client_id="              + enc(config.clientId())
                    + "&redirect_uri="           + enc(absoluteRedirectUri(req))
                    + "&scope="                  + enc(config.scopes())
                    + "&state="                  + state
                    + "&nonce="                  + enc(nonce)
                    + "&code_challenge="         + challenge
                    + "&code_challenge_method=S256";

            res.redirect(authUrl);
            return null;
        }).with();

        // ── GET {prefix}/callback ─────────────────────────────────────────────
        // Validates state, exchanges code for tokens, creates session, redirects.
        app.get(prefix + "/callback", (req, res) -> {
            String error = req.query("error");
            if (error != null) {
                res.status(400);
                return "Authentication error: " + error
                        + (req.query("error_description") != null
                           ? " — " + req.query("error_description") : "");
            }

            String code  = req.query("code");
            String state = req.query("state");

            OidcStateStore.Entry entry = stateStore.consumeAndRemove(state).orElse(null);
            if (entry == null) {
                res.status(400);
                return "Invalid or expired state parameter";
            }

            OidcTokenResponse tokens = tokenClient.exchangeCode(
                    meta.tokenEndpoint(), code, absoluteRedirectUri(req), entry.codeVerifier());

            // Validate ID token: signature + iss + aud + exp + iat + sub + nonce (OIDC Core §3.1.3.7)
            if (tokens.idToken() != null) {
                try {
                    validator.validateIdToken(tokens.idToken(), entry.nonce());
                } catch (OidcValidationException e) {
                    res.status(400);
                    return "ID token validation failed: " + e.getMessage();
                }
            }

            Map<String, Object> claims = mergeClaims(tokens);

            OidcSession session = new OidcSession(
                    UUID.randomUUID().toString(),
                    tokens.accessToken(),
                    tokens.idToken(),
                    tokens.refreshToken(),
                    Instant.now().plusSeconds(tokens.expiresIn()),
                    claims
            );
            config.sessionStore().save(session);

            res.header("Set-Cookie", sessionCookie(session.id()))
               .redirect(entry.originalUrl());
            return null;
        }).with();

        // ── POST {prefix}/logout ──────────────────────────────────────────────
        // Invalidates the local session and redirects to the provider's
        // end_session_endpoint (with id_token_hint) if available.
        app.post(prefix + "/logout", (req, res) -> {
            String sessionId   = OidcMiddleware.cookieValue(req, "oidc_session");
            String idTokenHint = null;

            if (sessionId != null) {
                config.sessionStore().find(sessionId)
                        .ifPresent(s -> {});          // capture id_token before delete
                OidcSession session = config.sessionStore().find(sessionId).orElse(null);
                if (session != null) idTokenHint = session.idToken();
                config.sessionStore().delete(sessionId);
            }

            String clearCookie = "oidc_session=; HttpOnly; Path=/; Max-Age=0; SameSite=Lax";
            String location;

            if (meta.endSessionEndpoint() != null) {
                String postLogout = absoluteSelf(req, config.postLogoutRedirectUri());
                StringBuilder url = new StringBuilder(meta.endSessionEndpoint())
                        .append("?post_logout_redirect_uri=").append(enc(postLogout));
                if (idTokenHint != null)
                    url.append("&id_token_hint=").append(enc(idTokenHint));
                location = url.toString();
            } else {
                location = config.postLogoutRedirectUri();
            }

            res.header("Set-Cookie", clearCookie)
               .redirect(location);
            return null;
        }).with();

        // 5. Annotation processor for @Authenticated / @RolesAllowed
        ctx.addAnnotationProcessor(handlerClass -> {
            RolesAllowed roles = handlerClass.getAnnotation(RolesAllowed.class);
            if (roles != null) return List.of(oidcMw.rolesMiddleware(roles.value()));

            Authenticated auth = handlerClass.getAnnotation(Authenticated.class);
            if (auth != null) return List.of(auth.optional()
                    ? oidcMw.optionalMiddleware()
                    : oidcMw.authenticatedMiddleware());

            return List.of();
        });

        // Register OpenAPI security scheme if flash-ext-openapi is on the classpath
        try {
            OpenApiIntegration.register(ctx, config, meta);
        } catch (NoClassDefFoundError ignored) {
            // flash-ext-openapi not available — OpenAPI integration disabled
        }
    }

    // -- Helpers --------------------------------------------------------------

    /**
     * Merges claims from both the access token and the ID token.
     * The access token carries provider-specific data like {@code realm_access.roles};
     * the ID token carries standard identity claims (sub, email, name, …).
     * ID token values win on conflict so that verified identity claims are authoritative.
     */
    private static Map<String, Object> mergeClaims(OidcTokenResponse tokens) {
        Map<String, Object> merged = new HashMap<>();
        // Access token first — provides roles, resource_access, etc.
        if (tokens.accessToken() != null) {
            merged.putAll(JwtUtils.parseClaims(tokens.accessToken()));
        }
        // ID token overrides — its identity claims (sub, email, name, …) take priority.
        if (tokens.idToken() != null) {
            merged.putAll(JwtUtils.parseClaims(tokens.idToken()));
        }
        return Map.copyOf(merged);
    }

    /**
     * Builds an {@link HttpClient}. If {@link OidcConfig#insecureTls()} is set,
     * installs a trust-all {@link SSLContext} that accepts any certificate.
     * <b>Only safe for development with self-signed certificates.</b>
     */
    private static HttpClient buildHttpClient(OidcConfig config) {
        if (!config.insecureTls()) return HttpClient.newHttpClient();
        try {
            TrustManager[] trustAll = { new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers()                          { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a)          {}
                public void checkServerTrusted(X509Certificate[] c, String a)          {}
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder().sslContext(ctx).build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create trust-all SSLContext", e);
        }
    }

    /**
     * Resolves the configured {@code redirectUri}. If it starts with {@code /},
     * prepends {@code selfScheme://Host} from the current request.
     */
    private String absoluteRedirectUri(dev.relism.models.Request req) {
        return absoluteSelf(req, config.redirectUri());
    }

    private String absoluteSelf(dev.relism.models.Request req, String uri) {
        if (!uri.startsWith("/")) return uri;
        return config.selfScheme() + "://" + req.header("Host") + uri;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String sessionCookie(String id) {
        return "oidc_session=" + id + "; HttpOnly; Path=/; SameSite=Lax";
    }

    /**
     * Loaded lazily so that {@code flash-ext-openapi} classes are only resolved at
     * runtime when {@link dev.relism.ext.openapi.OpenApiSecurityRegistry} is actually on the classpath.
     * If not present, the {@link NoClassDefFoundError} is caught at the call site.
     */
    private static final class OpenApiIntegration {
        static void register(dev.relism.extension.ExtensionContext ctx,
                             OidcConfig config, OidcProviderMetadata meta) {
            ctx.find(dev.relism.ext.openapi.OpenApiSecurityRegistry.class)
               .ifPresent(registry -> registry.add(new dev.relism.ext.openapi.OpenApiSecurityContributor() {

                   @Override
                   public String schemeName() { return config.schemeName(); }

                   @Override
                   public java.util.Map<String, Object> schemeDefinition() {
                       // Declare only the authorizationCode flow so Swagger UI shows
                       // a single clean "Authorize" dialog instead of expanding every
                       // grant type from the discovery document.
                       java.util.Map<String, String> scopesMap = new java.util.LinkedHashMap<>();
                       for (String s : config.scopes().split("\\s+")) {
                           if (!s.isBlank()) scopesMap.put(s, s);
                       }
                       java.util.Map<String, Object> flow = new java.util.LinkedHashMap<>();
                       flow.put("authorizationUrl", meta.authorizationEndpoint());
                       flow.put("tokenUrl",         meta.tokenEndpoint());
                       flow.put("scopes",           scopesMap);

                       java.util.Map<String, Object> scheme = new java.util.LinkedHashMap<>();
                       scheme.put("type",  "oauth2");
                       scheme.put("flows", java.util.Map.of("authorizationCode", flow));
                       return scheme;
                   }

                   @Override
                   public java.util.List<String> requiredFor(Class<?> handlerClass) {
                       dev.relism.ext.oidc.RolesAllowed roles =
                               handlerClass.getAnnotation(dev.relism.ext.oidc.RolesAllowed.class);
                       if (roles != null) return java.util.Arrays.asList(roles.value());

                       dev.relism.ext.oidc.Authenticated auth =
                               handlerClass.getAnnotation(dev.relism.ext.oidc.Authenticated.class);
                       if (auth != null) return java.util.List.of();

                       return null; // not secured by this contributor
                   }
               }));
        }
    }
}
