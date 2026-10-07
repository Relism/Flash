package dev.relism.flash.ext.security.oauthserver;

import dev.relism.flash.App;
import dev.relism.flash.Bound;
import dev.relism.flash.Module;
import dev.relism.flash.Services;
import dev.relism.flash.http.RequestAction;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.QueryParams;
import dev.relism.flash.routing.Cors;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.ext.cache.Cache;
import dev.relism.flash.ext.cache.CacheManager;
import dev.relism.flash.ext.security.AuthenticationFailedException;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.Tokens;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.ext.security.SecurityScheme;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.Middleware;
import dev.relism.fpr.core.ByteView;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An OAuth 2.1 authorization server for the application's own users and resources. A user signs in the
 * way the application's security chain signs anyone in, consents on the application's page, and the
 * client receives an RFC 9068 access token for one of the application's resources — which this module,
 * as an {@link AuthenticationMechanism}, then authenticates.
 *
 * <pre>{@code
 * app.install(new SecurityModule().origin("https://app.example").loginPage("/login"),
 *             new OAuthServer("/mcp").store(store).signingKeys(keys));
 * }</pre>
 *
 * <p>Authorization code with PKCE {@code S256} (the only grant a user takes part in), refresh tokens that
 * rotate and revoke their family on reuse, {@code client_credentials} for clients the application registers
 * itself; RFC 8414 metadata, RFC 7591 registration, client ID metadata documents, RFC 8707 resource
 * indicators, RFC 7009 revocation, RFC 9207 {@code iss} in the authorization response. The issuer is the
 * application's {@link Security#origin(Request)}. Needs a {@code CacheManager} in the app, for client
 * metadata documents and discovery.
 */
@Slf4j
public final class OAuthServer implements Module, AuthenticationMechanism, Bound {

    public static final String AUTHORIZE = "/oauth/authorize";
    public static final String TOKEN = "/oauth/token";
    public static final String REGISTER = "/oauth/register";
    public static final String REVOKE = "/oauth/revoke";
    public static final String JWKS = "/oauth/jwks";
    public static final String METADATA = "/.well-known/oauth-authorization-server";

    private static final AuthenticationFailedException INVALID = new AuthenticationFailedException("Bearer error=\"invalid_token\"");
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(1);

    private final List<String> resources;
    private OAuthStore store = new InMemoryOAuthStore();
    private SigningKeys keys;
    private Function<SecurityIdentity, OAuthSubject> subjects = identity -> new OAuthSubject(identity.principal().name(), Map.of());
    private Set<String> scopes = Set.of();
    private Duration accessTokenLifetime = Duration.ofMinutes(10);
    private Duration refreshTokenLifetime = Duration.ofDays(30);
    private String consentPage = "/consent";
    private boolean registration = true;
    private boolean localClients;
    private Clients clients;
    private Cache<String, String> metadata;
    private Security security;
    private Middleware signedIn;

    /**
     * @param resources the paths tokens may be issued for, as RFC 8707 resources on the application's origin;
     *                  the first is the audience of a request that names none
     */
    public OAuthServer(String... resources) {
        if (resources.length == 0) throw new IllegalArgumentException("Name at least one resource path, e.g. /mcp");
        for (String resource : resources) {
            if (!resource.startsWith("/")) throw new IllegalArgumentException("A resource is a path on this application: " + resource);
        }
        this.resources = List.of(resources);
    }

    // -- Configuration --------------------------------------------------------

    public OAuthServer store(OAuthStore store) {
        this.store = store;
        return this;
    }

    /**
     * The signing keys, as a JWK set whose first key is a private P-256 key — see {@link #generateSigningKeys()}.
     * Unset, a key is generated at boot, and every token dies with the process.
     */
    public OAuthServer signingKeys(String jwkSet) {
        this.keys = new SigningKeys(jwkSet);
        return this;
    }

    /**
     * Whom an authorization names, and what its tokens carry — and who may authorize at all: throw to refuse a
     * caller. A credential narrower than its user, like an API key, must not become a token with all the user's
     * rights. Default: the principal's name, no claims, anyone signed in.
     */
    public OAuthServer subjects(Function<SecurityIdentity, OAuthSubject> subjects) {
        this.subjects = subjects;
        return this;
    }

    /** The scopes a client may be granted; any other it asks for is left out of the grant. Default: none. */
    public OAuthServer scopes(String... scopes) {
        this.scopes = Set.of(scopes);
        return this;
    }

    public OAuthServer accessTokenLifetime(Duration lifetime) {
        this.accessTokenLifetime = lifetime;
        return this;
    }

    public OAuthServer refreshTokenLifetime(Duration lifetime) {
        this.refreshTokenLifetime = lifetime;
        return this;
    }

    /**
     * The application's page asking a signed-in user to allow a client, sent the authorization request as its
     * query string. It reads {@code GET /oauth/authorize/request} with that same query, and posts it back to
     * {@code /oauth/authorize} with {@code consent=allow} or {@code consent=deny}. Default {@code /consent}.
     */
    public OAuthServer consentPage(String consentPage) {
        this.consentPage = consentPage;
        return this;
    }

    /** Whether any client may register itself at {@link #REGISTER} (RFC 7591). Default {@code true}. */
    public OAuthServer registration(boolean open) {
        this.registration = open;
        return this;
    }

    /** Lets client metadata documents live on http and private addresses — for development, never production. */
    public OAuthServer allowLocalClients() {
        this.localClients = true;
        return this;
    }

    /** A new JWK set for {@link #signingKeys(String)}, private key included: generate once, keep it secret. */
    public static String generateSigningKeys() {
        return SigningKeys.generate();
    }

    // -- Clients --------------------------------------------------------------

    /** A registered client, and its secret — shown this once, never stored. {@code null} for a public client. */
    public record Registration(OAuthClient client, String secret) {}

    /**
     * Registers a client from its RFC 7591 metadata — what {@link #REGISTER} does for a client registering
     * itself, and the only way to a {@code client_credentials} client.
     */
    public Registration register(Map<String, Object> metadata) {
        return register(metadata, true);
    }

    private Registration register(Map<String, Object> metadata, boolean trusted) {
        Map<String, Object> valid = Clients.validate(metadata, trusted);
        String secret = "none".equals(valid.get("token_endpoint_auth_method")) ? null : Tokens.random(32);
        OAuthClient client = new OAuthClient(Tokens.random(16), secret == null ? null : s256(secret), valid);
        store.save(client);
        return new Registration(client, secret);
    }

    // -- Extension ------------------------------------------------------------

    @Override
    public void install(App app) {
        if (keys == null) {
            keys = new SigningKeys(SigningKeys.generate());
            log.warn("[flash-ext-security-oauth-server] No signing keys configured: tokens will not survive a restart.");
        }
        // Resolved when routes compile, after bind: the app's Security is not known before it starts.
        Middleware requireSignIn = (exchange, next) -> signedIn.handle(exchange, next);
        Cors cors = Cors.allow().methods("GET", "POST", "OPTIONS").headers("Authorization", "Content-Type");
        app.add(OAuthServer.class, this)
                .add(AuthenticationMechanism.class, this)
                .get(AUTHORIZE, this::authorize, requireSignIn)
                .post(AUTHORIZE, this::decide, requireSignIn)
                .get(AUTHORIZE + "/request", this::request, requireSignIn)
                .get(METADATA, (req, res) -> json(res, 200, metadata.get(issuer(req), this::describe)), cors)
                .get(JWKS, (req, res) -> json(res, 200, keys.publicJwks()), cors)
                .post(TOKEN, endpoint(this::token), cors)
                .post(REVOKE, endpoint(this::revoke), cors);
        if (registration) app.post(REGISTER, endpoint(this::registerSelf), cors);
        // A browser client's preflight: cors answers it before the no-op handler runs.
        for (String path : registration ? List.of(METADATA, JWKS, TOKEN, REVOKE, REGISTER) : List.of(METADATA, JWKS, TOKEN, REVOKE)) {
            app.route(HttpMethod.OPTIONS, path, (req, res) -> null, cors);
        }
    }

    @Override
    public void bind(Services services) {
        security = services.get(Security.class);
        signedIn = security.enforce(SecurityPolicy.AUTHENTICATED);
        CacheManager caches = services.get(CacheManager.class);
        clients = new Clients(store, localClients, caches.build("oauth-server.client-documents", spec -> spec.maxSize(1_000).ttl(Duration.ofMinutes(10))));
        // Keyed by issuer, which is the request's own origin when none is configured: bounded for that.
        metadata = caches.build("oauth-server.metadata", spec -> spec.maxSize(16));
    }

    // -- Bearer access tokens -------------------------------------------------

    /**
     * Only tokens this server issued are this mechanism's: any other bearer is left to the rest of the chain.
     * A token is good only under the resource it was issued for (RFC 8707, RFC 9068 §4): one for {@code /mcp}
     * authenticates nothing outside it.
     */
    @Override
    public Principal authenticate(Request req) {
        String header = req.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        SignedJWT token;
        try {
            token = SignedJWT.parse(header.substring(7));
            if (!issuer(req).equals(token.getJWTClaimsSet().getIssuer())) return null;
        } catch (java.text.ParseException notAJwt) {
            return null;
        }
        try {
            Map<String, Object> claims = keys.verify(token);
            OAuthPrincipal principal = new OAuthPrincipal((String) claims.get("sub"), (String) claims.get("client_id"), claims);
            String resource = resourceAt(req.path());
            if (resource != null && principal.hasAudience(issuer(req) + resource)) return principal;
        } catch (Exception invalid) {
            // Refused below, like a token for another resource.
        }
        throw INVALID;
    }

    /** The innermost of this server's resources {@code path} lies in, or {@code null}. */
    private String resourceAt(String path) {
        String found = null;
        for (String resource : resources) {
            boolean within = resource.equals("/") || path.equals(resource) || path.startsWith(resource + "/");
            if (within && (found == null || resource.length() > found.length())) found = resource;
        }
        return found;
    }

    /** Its issuer is {@code "/"}: this application, at whatever origin it is reached by. */
    @Override
    public List<SecurityScheme> schemes() {
        Map<String, Object> flow = Map.of("authorizationUrl", AUTHORIZE, "tokenUrl", TOKEN, "refreshUrl", TOKEN,
                "scopes", scopes.stream().collect(Collectors.toMap(scope -> scope, scope -> scope)));
        return List.of(new SecurityScheme("oauth", Map.of("type", "oauth2", "flows", Map.of("authorizationCode", flow)), "Bearer realm=\"oauth\"", "/"));
    }

    // -- Authorization endpoint -----------------------------------------------

    /** A validated authorization request. */
    private record Authorization(OAuthClient client, String redirectUri, String requestedRedirect, String state, String scope,
                                 String resource, String challenge) {}

    /** An RFC 6749 / RFC 7591 error: its code, what to tell the client, and the status to answer with. */
    static final class OAuthError extends RuntimeException {
        final String error;
        final int status;

        OAuthError(String error, String description) {
            this(400, error, description);
        }

        OAuthError(int status, String error, String description) {
            super(description, null, false, false);
            this.error = error;
            this.status = status;
        }
    }

    /** A request whose client and redirect URI are good, and which is refused anyway: answered at the redirect URI. */
    private static final class Refused extends RuntimeException {
        final String error;
        final String redirectUri;
        final String state;

        Refused(String error, String description, String redirectUri, String state) {
            super(description, null, false, false);
            this.error = error;
            this.redirectUri = redirectUri;
            this.state = state;
        }
    }

    private Object authorize(Request req, Response res) {
        try {
            Authorization authorization = authorization(req::query, req);
            OAuthSubject subject = subjects.apply(SecurityIdentity.current());
            String consented = store.consent(subject.id(), authorization.client().id());
            if (consented != null && covers(consented, authorization.scope())) return back(req, res, 302, authorization, subject);
            res.redirect(consentPage + "?" + query(req));
        } catch (Refused refused) {
            refuse(req, res, 302, refused);
        }
        return null;
    }

    /** The consent page's answer. SameSite=Lax keeps another site's form from carrying the session here. */
    private Object decide(Request req, Response res) {
        String origin = req.header("Origin");
        if (origin != null && !origin.equals(issuer(req))) throw HttpException.forbidden();
        QueryParams form = req.form();
        try {
            Authorization authorization = authorization(form::get, req);
            if (!"allow".equals(form.get("consent"))) {
                throw new Refused("access_denied", "The user did not allow it", authorization.redirectUri(), authorization.state());
            }
            OAuthSubject subject = subjects.apply(SecurityIdentity.current());
            store.consent(subject.id(), authorization.client().id(), authorization.scope());
            return back(req, res, 303, authorization, subject);
        } catch (Refused refused) {
            refuse(req, res, 303, refused);
            return null;
        }
    }

    /** What a consent page shows about the request it was sent. */
    private Object request(Request req, Response res) {
        try {
            Authorization authorization = authorization(req::query, req);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("client_id", authorization.client().id());
            view.put("client_name", authorization.client().name());
            for (String key : List.of("client_uri", "logo_uri")) {
                if (authorization.client().metadata().get(key) != null) view.put(key, authorization.client().metadata().get(key));
            }
            view.put("redirect_uri", authorization.redirectUri());
            view.put("scope", authorization.scope());
            view.put("resource", authorization.resource());
            return json(res, 200, JSONObjectUtils.toJSONString(view));
        } catch (Refused refused) {
            return json(res, 400, JSONObjectUtils.toJSONString(Map.of("error", refused.error, "error_description", refused.getMessage())));
        }
    }

    /**
     * An unknown client or an unregistered redirect URI is refused here and never redirected (OAuth 2.1
     * §4.1.2.1): redirecting would hand the response to whoever wrote the URI. Anything else goes back to it.
     */
    private Authorization authorization(Function<String, String> param, Request req) {
        OAuthClient client = clients.find(param.apply("client_id"));
        if (client == null) throw HttpException.badRequest("Unknown client");
        String requested = param.apply("redirect_uri");
        List<String> registered = client.redirectUris();
        String redirectUri = requested == null ? registered.size() == 1 ? registered.getFirst() : null
                : registered.stream().anyMatch(uri -> Clients.matches(uri, requested)) ? requested : null;
        if (redirectUri == null) throw HttpException.badRequest("Unregistered redirect_uri");
        String state = param.apply("state");
        if (!"code".equals(param.apply("response_type"))) throw new Refused("unsupported_response_type", "Only code is supported", redirectUri, state);
        if (!client.grantTypes().contains("authorization_code")) throw new Refused("unauthorized_client", "Not registered for authorization_code", redirectUri, state);
        String challenge = param.apply("code_challenge");
        if (challenge == null || !"S256".equals(param.apply("code_challenge_method"))) {
            throw new Refused("invalid_request", "PKCE with S256 is required", redirectUri, state);
        }
        String resource = resource(param.apply("resource"), req);
        if (resource == null) throw new Refused("invalid_target", "Unknown resource", redirectUri, state);
        return new Authorization(client, redirectUri, requested, state, granted(param.apply("scope")), resource, challenge);
    }

    /** A code for the authorization, delivered at the client's redirect URI. */
    private Object back(Request req, Response res, int status, Authorization authorization, OAuthSubject subject) {
        String code = Tokens.random(32);
        store.save(new OAuthGrant(s256(code), OAuthGrant.Kind.CODE, Tokens.random(16), authorization.client().id(), subject.id(), subject.claims(),
                authorization.scope(), authorization.resource(), authorization.requestedRedirect(), authorization.challenge(),
                Instant.now().plus(CODE_LIFETIME), null));
        res.status(status).header("Location", redirect(authorization.redirectUri(), "code", code, authorization.state(), issuer(req)));
        return null;
    }

    private void refuse(Request req, Response res, int status, Refused refused) {
        res.status(status).header("Location", redirect(refused.redirectUri, "error", refused.error, refused.state, issuer(req)));
    }

    /** RFC 9207: the issuer travels with the response, so a client talking to several servers cannot be mixed up. */
    private static String redirect(String uri, String key, String value, String state, String issuer) {
        return uri + (uri.indexOf('?') < 0 ? '?' : '&') + key + "=" + encode(value)
                + (state == null ? "" : "&state=" + encode(state)) + "&iss=" + encode(issuer);
    }

    // -- Token endpoint -------------------------------------------------------

    private Object token(Request req, Response res) {
        QueryParams form = req.form();
        OAuthClient client = client(req, form);
        String grantType = form.get("grant_type");
        if (grantType == null) throw new OAuthError("invalid_request", "grant_type is required");
        if (!client.grantTypes().contains(grantType)) throw new OAuthError("unauthorized_client", "Not registered for " + grantType);
        return json(res, 200, switch (grantType) {
            case "authorization_code" -> exchange(req, client, form);
            case "refresh_token" -> refresh(req, client, form);
            case "client_credentials" -> {
                String resource = resource(form.get("resource"), req);
                if (resource == null) throw new OAuthError("invalid_target", "Unknown resource");
                yield issue(req, client, client.id(), Map.of(), granted(form.get("scope")), resource, null);
            }
            default -> throw new OAuthError("unsupported_grant_type", "Unsupported grant_type");
        });
    }

    private String exchange(Request req, OAuthClient client, QueryParams form) {
        OAuthGrant code = use(form.get("code"), OAuthGrant.Kind.CODE, client);
        if (code.redirectUri() != null && !code.redirectUri().equals(form.get("redirect_uri"))) throw new OAuthError("invalid_grant", "redirect_uri does not match");
        String verifier = form.get("code_verifier");
        if (verifier == null || !MessageDigest.isEqual(s256(verifier).getBytes(StandardCharsets.US_ASCII), code.challenge().getBytes(StandardCharsets.US_ASCII))) {
            throw new OAuthError("invalid_grant", "code_verifier does not match");
        }
        if (form.get("resource") != null && !form.get("resource").equals(code.resource())) throw new OAuthError("invalid_target", "Not the resource authorized");
        return issue(req, client, code.subject(), code.claims(), code.scope(), code.resource(), code.family());
    }

    /** Rotates: the token used is spent, and its successor carries the same authorization — or less of it. */
    private String refresh(Request req, OAuthClient client, QueryParams form) {
        OAuthGrant refresh = use(form.get("refresh_token"), OAuthGrant.Kind.REFRESH, client);
        String scope = form.get("scope") == null ? refresh.scope() : form.get("scope");
        if (!covers(refresh.scope(), scope)) throw new OAuthError("invalid_scope", "More than was authorized");
        if (form.get("resource") != null && !form.get("resource").equals(refresh.resource())) throw new OAuthError("invalid_target", "Not the resource authorized");
        return issue(req, client, refresh.subject(), refresh.claims(), scope, refresh.resource(), refresh.family());
    }

    /** A code or refresh token spent now. One already spent revokes everything issued from the same authorization. */
    private OAuthGrant use(String value, OAuthGrant.Kind kind, OAuthClient client) {
        OAuthGrant grant = value == null ? null : store.use(s256(value));
        if (grant == null || grant.kind() != kind || !grant.clientId().equals(client.id())) throw new OAuthError("invalid_grant", "Unknown " + kind.name().toLowerCase());
        if (grant.usedAt() != null) {
            store.revoke(grant.family());
            throw new OAuthError("invalid_grant", "Already used: every token from this authorization is revoked");
        }
        if (grant.expiresAt().isBefore(Instant.now())) throw new OAuthError("invalid_grant", "Expired");
        return grant;
    }

    /** @param family {@code null} for {@code client_credentials}, which gets no refresh token */
    private String issue(Request req, OAuthClient client, String subject, Map<String, Object> extra, String scope, String resource, String family) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>(extra);
        claims.putAll(Map.of("iss", issuer(req), "sub", subject, "aud", resource, "client_id", client.id(), "jti", Tokens.random(16),
                "iat", Date.from(now), "exp", Date.from(now.plus(accessTokenLifetime))));
        if (!scope.isEmpty()) claims.put("scope", scope);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", keys.sign(claims));
        response.put("token_type", "Bearer");
        response.put("expires_in", accessTokenLifetime.toSeconds());
        if (!scope.isEmpty()) response.put("scope", scope);
        if (family != null && client.grantTypes().contains("refresh_token")) {
            String refresh = Tokens.random(32);
            store.save(new OAuthGrant(s256(refresh), OAuthGrant.Kind.REFRESH, family, client.id(), subject, extra, scope, resource,
                    null, null, now.plus(refreshTokenLifetime), null));
            response.put("refresh_token", refresh);
        }
        return JSONObjectUtils.toJSONString(response);
    }

    // -- Revocation and registration ------------------------------------------

    /** RFC 7009: a refresh token takes its whole authorization with it. An access token simply expires; an unknown token is no error. */
    private Object revoke(Request req, Response res) {
        QueryParams form = req.form();
        OAuthClient client = client(req, form);
        OAuthGrant grant = form.get("token") == null ? null : store.find(s256(form.get("token")));
        if (grant != null && !grant.clientId().equals(client.id())) throw new OAuthError("unauthorized_client", "Not this client's token");
        if (grant != null) store.revoke(grant.family());
        res.status(200);
        return null;
    }

    private Object registerSelf(Request req, Response res) {
        Map<String, Object> metadata;
        try {
            metadata = JSONObjectUtils.parse(new String(req.body().bytes(), StandardCharsets.UTF_8));
        } catch (java.text.ParseException malformed) {
            throw new OAuthError("invalid_client_metadata", "Not a JSON object");
        }
        Registration registration = register(metadata, false);
        Map<String, Object> response = new LinkedHashMap<>(registration.client().metadata());
        response.put("client_id", registration.client().id());
        response.put("client_id_issued_at", Instant.now().getEpochSecond());
        if (registration.secret() != null) {
            response.put("client_secret", registration.secret());
            response.put("client_secret_expires_at", 0);
        }
        return json(res, 201, JSONObjectUtils.toJSONString(response));
    }

    // -- Metadata ---------------------------------------------------------------

    /** RFC 8414, plus what the MCP authorization spec asks a server to say about client ID metadata documents. */
    private String describe(String issuer) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer);
        metadata.put("authorization_endpoint", issuer + AUTHORIZE);
        metadata.put("token_endpoint", issuer + TOKEN);
        if (registration) metadata.put("registration_endpoint", issuer + REGISTER);
        metadata.put("revocation_endpoint", issuer + REVOKE);
        metadata.put("jwks_uri", issuer + JWKS);
        if (!scopes.isEmpty()) metadata.put("scopes_supported", List.copyOf(scopes));
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("response_modes_supported", List.of("query"));
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token", "client_credentials"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("token_endpoint_auth_methods_supported", Clients.AUTH_METHODS);
        metadata.put("revocation_endpoint_auth_methods_supported", Clients.AUTH_METHODS);
        metadata.put("authorization_response_iss_parameter_supported", true);
        metadata.put("client_id_metadata_document_supported", true);
        return JSONObjectUtils.toJSONString(metadata);
    }

    // -- Helpers ----------------------------------------------------------------

    /** RFC 6749 §5.2: every failure is a JSON error with its code; nothing here is ever cached. */
    private static RequestAction endpoint(RequestAction handler) {
        return (req, res) -> {
            res.header("Cache-Control", "no-store");
            try {
                return handler.handle(req, res);
            } catch (OAuthError invalid) {
                if (invalid.status == 401) res.header("WWW-Authenticate", "Basic realm=\"oauth\"");
                return json(res, invalid.status, JSONObjectUtils.toJSONString(Map.of("error", invalid.error, "error_description", invalid.getMessage())));
            }
        };
    }

    /**
     * The client making a token or revocation request, authenticated as it registered: a secret in the
     * {@code Authorization} header, a secret in the form, or — a public client — its id alone.
     */
    private OAuthClient client(Request req, QueryParams form) {
        String header = req.header("Authorization");
        String id = form.get("client_id");
        String secret = form.get("client_secret");
        String method = secret != null ? "client_secret_post" : "none";
        if (header != null && header.startsWith("Basic ")) {
            String[] pair = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8).split(":", 2);
            if (pair.length != 2) throw new OAuthError(401, "invalid_client", "Malformed Basic credentials");
            id = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            secret = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            method = "client_secret_basic";
        }
        OAuthClient client = clients.find(id);
        boolean authenticated = client != null && client.authMethod().equals(method)
                && (method.equals("none") || MessageDigest.isEqual(s256(secret).getBytes(StandardCharsets.US_ASCII), client.secretHash().getBytes(StandardCharsets.US_ASCII)));
        if (!authenticated) throw new OAuthError(401, "invalid_client", "Client authentication failed");
        return client;
    }

    /** RFC 8707: one of this application's resources, or the first when the request names none. */
    private String resource(String requested, Request req) {
        String origin = issuer(req);
        if (requested == null) return origin + resources.getFirst();
        return resources.stream().map(path -> origin + path).filter(requested::equals).findFirst().orElse(null);
    }

    /** What was asked for, less what this server does not grant. */
    private String granted(String requested) {
        if (requested == null) return "";
        return Arrays.stream(requested.split(" ")).filter(scopes::contains).distinct().collect(Collectors.joining(" "));
    }

    private static boolean covers(String granted, String requested) {
        return List.of(granted.split(" ")).containsAll(List.of(requested.split(" ")));
    }

    private String issuer(Request req) {
        return security.origin(req);
    }

    private static Object json(Response res, int status, String body) {
        res.status(status).type(ContentType.JSON);
        return body;
    }

    private static String query(Request req) {
        ByteView query = req.rawQuery();
        byte[] raw = new byte[query == null ? 0 : query.length()];
        for (int i = 0; i < raw.length; i++) raw[i] = query.byteAt(i);
        return new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * PKCE's {@code S256}, and how codes, refresh tokens and client secrets are stored: they are 256 random
     * bits, so a slow KDF would protect nothing.
     */
    private static String s256(String verifier) {
        try {
            return BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
