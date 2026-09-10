package dev.relism.flash.ext.oidc;

import dev.relism.flash.ext.auth.InMemorySessionStore;
import dev.relism.flash.ext.auth.SessionStore;

/**
 * Full OIDC client configuration. Build via
 * {@link #builder(String, String, String, String)} or {@link #fromEnv()}.
 *
 * <p>Required fields: {@code issuer}, {@code clientId}, {@code clientSecret},
 * {@code redirectUri}. Everything else has a sensible default.
 *
 * <p>If {@code redirectUri} starts with {@code /} it is treated as server-relative:
 * the absolute URL is resolved at request time using {@link #selfScheme()} and the
 * incoming {@code Host} header. Use {@link Builder#https()} when behind TLS.
 *
 * <pre>{@code
 * // Keycloak
 * OidcConfig.builder(
 *         "https://keycloak.example.com/realms/myrealm",
 *         "my-app", "secret", "/auth/callback")
 *     .rolesClaimPath("realm_access.roles")   // Keycloak default
 *     .scopeClaimPaths("scope,scp")           // default; supports many IdPs
 *     .build();
 *
 * // Authelia
 * OidcConfig.builder(
 *         "https://auth.example.com",
 *         "my-app", "secret", "/auth/callback")
 *     .rolesClaimPath("groups")
 *     .scopeClaimPaths("scope,scp")
 *     .build();
 *
 * // Two tenants on one server
 * OidcConfig tenantA = OidcConfig.builder("https://idp/realms/a", ..., "/tenantA/auth/callback")
 *     .routePrefix("/tenantA/auth").build();
 * OidcConfig tenantB = OidcConfig.builder("https://idp/realms/b", ..., "/tenantB/auth/callback")
 *     .routePrefix("/tenantB/auth").build();
 * app.install(new OidcExtension(tenantA))
 *    .install(new OidcExtension(tenantB));
 * }</pre>
 */
public final class OidcConfig {

    private final String           issuer;
    private final String           clientId;
    private final String           clientSecret;
    private final String           redirectUri;
    private final String           scopes;
    private final String           routePrefix;
    private final String           selfScheme;
    private final String           rolesClaimPath;
    private final String           scopeClaimPaths;
    private final String           algorithm;
    private final String           postLogoutRedirectUri;
    private final SessionStore sessionStore;
    private final boolean          insecureTls;
    private final ClientAuthMethod clientAuthMethod;
    private final String           schemeName;

    private OidcConfig(Builder b) {
        this.issuer                = require(b.issuer,       "issuer");
        this.clientId              = require(b.clientId,     "clientId");
        this.clientSecret          = require(b.clientSecret, "clientSecret");
        this.redirectUri           = require(b.redirectUri,  "redirectUri");
        this.scopes                = b.scopes;
        this.routePrefix           = b.routePrefix;
        this.selfScheme            = b.selfScheme;
        this.rolesClaimPath        = b.rolesClaimPath;
        this.scopeClaimPaths       = b.scopeClaimPaths;
        this.algorithm             = b.algorithm;
        this.postLogoutRedirectUri = b.postLogoutRedirectUri;
        this.sessionStore          = b.sessionStore != null ? b.sessionStore
                                                            : new InMemorySessionStore();
        this.insecureTls           = b.insecureTls;
        this.clientAuthMethod      = b.clientAuthMethod;
        this.schemeName            = b.schemeName != null ? b.schemeName : deriveScheme(this.issuer);
    }

    // -- Getters --------------------------------------------------------------

    public String           issuer()                { return issuer; }
    public String           clientId()              { return clientId; }
    public String           clientSecret()          { return clientSecret; }
    public String           redirectUri()           { return redirectUri; }
    public String           scopes()                { return scopes; }
    public String           routePrefix()           { return routePrefix; }
    public String           selfScheme()            { return selfScheme; }
    public String           rolesClaimPath()        { return rolesClaimPath; }
    /** Comma-separated claim paths used to read OAuth2 scopes (default: {@code "scope,scp"}). */
    public String           scopeClaimPaths()       { return scopeClaimPaths; }
    public String           algorithm()             { return algorithm; }
    public String           postLogoutRedirectUri() { return postLogoutRedirectUri; }
    public SessionStore sessionStore()          { return sessionStore; }
    /** If {@code true}, TLS certificate validation is skipped. <b>Never use in production.</b> */
    public boolean          insecureTls()           { return insecureTls; }
    public ClientAuthMethod clientAuthMethod()       { return clientAuthMethod; }
    /** OpenAPI security scheme name (derived from issuer if not set explicitly). */
    public String           schemeName()            { return schemeName; }

    // -- Factory --------------------------------------------------------------

    /**
     * Reads configuration from environment variables:
     * <pre>
     * OIDC_ISSUER               required
     * OIDC_CLIENT_ID            required
     * OIDC_CLIENT_SECRET        required
     * OIDC_REDIRECT_URI         required  (e.g. /auth/callback)
     * OIDC_SCOPES               default: openid profile email
     * OIDC_ROUTE_PREFIX         default: /auth
     * OIDC_SELF_SCHEME          default: http
     * OIDC_ROLES_CLAIM          default: realm_access.roles
     * OIDC_SCOPE_CLAIMS         default: scope,scp
     * OIDC_ALGORITHM            default: RS256
     * OIDC_POST_LOGOUT_REDIRECT default: /
     * </pre>
     */
    public static OidcConfig fromEnv() {
        return builder(env("OIDC_ISSUER"), env("OIDC_CLIENT_ID"),
                       env("OIDC_CLIENT_SECRET"), env("OIDC_REDIRECT_URI"))
                .scopes               (envOr("OIDC_SCOPES",               "openid profile email"))
                .routePrefix          (envOr("OIDC_ROUTE_PREFIX",          "/auth"))
                .selfScheme           (envOr("OIDC_SELF_SCHEME",           "http"))
                .rolesClaimPath       (envOr("OIDC_ROLES_CLAIM",           "realm_access.roles"))
                .scopeClaimPaths      (envOr("OIDC_SCOPE_CLAIMS",          "scope,scp"))
                .algorithm            (envOr("OIDC_ALGORITHM",             "RS256"))
                .postLogoutRedirectUri(envOr("OIDC_POST_LOGOUT_REDIRECT",  "/"))
                .clientAuthMethod(ClientAuthMethod.valueOf(
                        envOr("OIDC_CLIENT_AUTH_METHOD", "POST").toUpperCase()))
                .build();
    }

    public static Builder builder(String issuer, String clientId,
                                  String clientSecret, String redirectUri) {
        return new Builder(issuer, clientId, clientSecret, redirectUri);
    }

    /**
     * Convenience factory for Keycloak: constructs the issuer as
     * {@code {serverUrl}/realms/{realm}} automatically.
     *
     * <pre>{@code
     * OidcConfig.keycloak(
     *     "https://keycloak.example.com", "flashboard",
     *     "my-app", "secret", "/auth/callback")
     *   .https()
     *   .build();
     * }</pre>
     */
    public static Builder keycloak(String serverUrl, String realm,
                                   String clientId, String clientSecret,
                                   String redirectUri) {
        String base   = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        String issuer = base + "/realms/" + realm;
        return new Builder(issuer, clientId, clientSecret, redirectUri)
                .rolesClaimPath("realm_access.roles");   // Keycloak default
    }

    // -- Helpers --------------------------------------------------------------

    private static String require(String v, String name) {
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("OidcConfig: " + name + " is required");
        return v;
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("Missing required env var: " + key);
        return v;
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }

    // -- Builder --------------------------------------------------------------

    public static final class Builder {

        private final String issuer;
        private final String clientId;
        private final String clientSecret;
        private final String redirectUri;

        private String           scopes                = "openid profile email";
        private String           routePrefix           = "/auth";
        private String           selfScheme            = "http";
        private String           rolesClaimPath        = "realm_access.roles";
        private String           scopeClaimPaths       = "scope,scp";
        private String           algorithm             = "RS256";
        private String           postLogoutRedirectUri = "/";
        private SessionStore sessionStore;
        private boolean          insecureTls           = false;
        private ClientAuthMethod clientAuthMethod       = ClientAuthMethod.POST;
        private String           schemeName            = null;

        private Builder(String issuer, String clientId, String clientSecret, String redirectUri) {
            this.issuer       = issuer;
            this.clientId     = clientId;
            this.clientSecret = clientSecret;
            this.redirectUri  = redirectUri;
        }

        /** Override requested scopes (default: {@code openid profile email}). */
        public Builder scopes(String scopes)                        { this.scopes = scopes;                               return this; }
        /** Route prefix for login/callback/logout (default: {@code /auth}). */
        public Builder routePrefix(String prefix)                   { this.routePrefix = prefix;                          return this; }
        /** Scheme used when resolving self-relative redirect URIs (default: {@code http}). */
        public Builder selfScheme(String scheme)                    { this.selfScheme = scheme;                           return this; }
        /** Shorthand for {@code selfScheme("https")}. */
        public Builder https()                                      { return selfScheme("https"); }
        /** Dot-separated path to the roles array in JWT claims (default: {@code realm_access.roles}). */
        public Builder rolesClaimPath(String path)                  { this.rolesClaimPath = path;                         return this; }
        /** Comma-separated claim paths used to resolve OAuth2 scopes (default: {@code scope,scp}). */
        public Builder scopeClaimPaths(String paths)                { this.scopeClaimPaths = paths;                       return this; }
        /** JWS algorithm (default: {@code RS256}). */
        public Builder algorithm(String algorithm)                  { this.algorithm = algorithm;                         return this; }
        /** Where to redirect after logout (default: {@code /}). */
        public Builder postLogoutRedirectUri(String uri)            { this.postLogoutRedirectUri = uri;                   return this; }
        /** Custom session store (default: {@link InMemorySessionStore}). */
        public Builder sessionStore(SessionStore store)         { this.sessionStore = store;                          return this; }
        /**
         * Disables TLS certificate verification for all HTTP calls made by this extension.
         * <b>Only use in development with self-signed certificates — never in production.</b>
         */
        public Builder insecureTls()                                { this.insecureTls = true;                            return this; }
        /** Token endpoint client authentication method (default: {@link ClientAuthMethod#POST}). */
        public Builder clientAuthMethod(ClientAuthMethod method)    { this.clientAuthMethod = method;                     return this; }
        /** Override the OpenAPI security scheme name (default: derived from the issuer URI). */
        public Builder schemeName(String name)                      { this.schemeName = name;                             return this; }

        public OidcConfig build() { return new OidcConfig(this); }
    }

    /**
     * Derives a short, human-readable scheme name from the issuer URI.
     * Takes the last non-empty path segment; falls back to the host.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code https://keycloak.dev.home/realms/flashboard} → {@code "flashboard"}</li>
     *   <li>{@code https://auth.example.com}                    → {@code "auth.example.com"}</li>
     * </ul>
     */
    private static String deriveScheme(String issuer) {
        try {
            java.net.URI uri = new java.net.URI(issuer);
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                String[] parts = path.split("/");
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (!parts[i].isEmpty()) return parts[i];
                }
            }
            return uri.getHost();
        } catch (Exception e) {
            return "oidc";
        }
    }
}
