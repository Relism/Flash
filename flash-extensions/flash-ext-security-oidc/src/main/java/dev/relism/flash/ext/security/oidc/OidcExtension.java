package dev.relism.flash.ext.security.oidc;

import com.nimbusds.jwt.SignedJWT;
import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.ext.security.AuthenticationFailedException;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.LoginMethod;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.ext.security.SecurityScheme;
import dev.relism.flash.ext.security.Session;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenID Connect: bearer access tokens from any configured provider, and browser sign-in through the
 * authorization code flow with PKCE, which ends in a {@link SecurityExtension} session renewed with
 * the refresh token.
 *
 * <pre>{@code
 * app.install(new SecurityExtension())
 *    .install(new OidcExtension(OidcProvider.of("sso", "https://id.example.com/realms/acme", "app", secret)));
 * }</pre>
 *
 * <p>Routes: {@code GET /auth/oidc/{provider}/login?redirect=/path} and its callback. A bearer token
 * is matched to its provider by {@code iss} before its signature is checked against that provider's
 * keys, so any number of providers costs one lookup; a token from an issuer not configured here is
 * left to other mechanisms.
 */
public final class OidcExtension implements FlashExtension, AuthenticationMechanism {

    private static final AuthenticationFailedException INVALID = new AuthenticationFailedException("Bearer error=\"invalid_token\"");
    private static final String FLOW = "flash_oidc";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final OidcProvider[] configured;
    private final Map<String, Provider> byId = new ConcurrentHashMap<>();
    private final Map<String, Provider> byIssuer = new ConcurrentHashMap<>();
    private SecurityExtension security;
    private boolean localProviders;

    public OidcExtension(OidcProvider... providers) {
        this.configured = providers;
    }

    /** Lets {@link #register} accept http and private addresses — for a provider on a developer's machine, never in production. */
    public OidcExtension allowLocalProviders() {
        this.localProviders = true;
        return this;
    }

    /**
     * Trusts another provider from now on — an organization connecting its own. Discovery runs here,
     * so a bad provider fails this call. It serves bearer tokens and {@code /auth/oidc/{id}/login}, but is
     * not listed at {@code /auth/methods}: which provider a user signs in with is the application's call.
     *
     * @throws IllegalArgumentException the provider, or an endpoint it names, is not https on a public address,
     *                                  or its id is a configured provider's, which it would otherwise replace
     */
    public void register(OidcProvider config) {
        for (OidcProvider fixed : configured) {
            if (fixed.id().equals(config.id())) throw new IllegalArgumentException("A configured provider already uses the id " + config.id());
        }
        Provider provider = new Provider(config, !localProviders);
        Provider replaced = byId.put(config.id(), provider);
        if (replaced != null) byIssuer.remove(replaced.issuer);
        byIssuer.put(provider.issuer, provider);
    }

    /**
     * Checks a provider before trusting it: discovery, the client ID and secret, and the redirect URI a
     * sign-in from {@code origin} will send. Registers nothing, and holds {@link #register}'s address rules.
     *
     * @param origin where users will sign in from, as {@link Request#origin()} gives it
     * @throws IllegalArgumentException naming what the provider refused
     * @throws IllegalStateException the provider could not be reached
     */
    public void verify(OidcProvider config, String origin) {
        new Provider(config, !localProviders).verify(callbackUri(origin, config.id()));
    }

    /** Stops trusting a provider: its tokens are no longer this mechanism's, its sessions stop renewing. */
    public void unregister(String id) {
        Provider removed = byId.remove(id);
        if (removed != null) byIssuer.remove(removed.issuer);
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        for (OidcProvider config : configured) {
            Provider provider = new Provider(config, false);
            byId.put(config.id(), provider);
            byIssuer.put(provider.issuer, provider);
        }
        ctx.provide(OidcExtension.class, this);
        app.get("/auth/oidc/{provider}/login", this::login);
        app.get("/auth/oidc/{provider}/callback", this::callback);
        ctx.onReady(() -> {
            security = ctx.require(SecurityExtension.class).mechanism(this).refresher(OidcPrincipal.class, this::refresh);
            for (OidcProvider config : configured) {
                security.scheme(SecurityScheme.openIdConnect(config.id(), byId.get(config.id()).issuer))
                        .loginMethod(new LoginMethod(config.id(), config.name(), "/auth/oidc/" + config.id() + "/login", LoginMethod.Kind.REDIRECT));
            }
        });
    }

    // -- Bearer access tokens -------------------------------------------------

    @Override
    public Principal authenticate(Request req) {
        String header = req.header("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.indexOf('.') < 0 || header.indexOf('.') == header.lastIndexOf('.')) return null;
        String token = header.substring(7);
        Provider provider;
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
            provider = byIssuer.get(String.valueOf(jwt.getJWTClaimsSet().getIssuer()));
        } catch (Exception malformed) {
            throw INVALID;
        }
        if (provider == null) return null;
        try {
            Map<String, Object> claims = provider.verifyAccessToken(jwt);
            return new OidcPrincipal(provider.config.id(), (String) claims.get("sub"), claims, token, null, null);
        } catch (Exception invalid) {
            throw INVALID;
        }
    }

    // -- Browser sign-in --------------------------------------------------------

    private Object login(Request req, Response res) throws Exception {
        Provider provider = provider(req);
        String state = random(16);
        String verifier = random(32);
        String nonce = random(16);
        res.header("Set-Cookie", FLOW + "=" + state + "." + verifier + "." + nonce + "."
                + BASE64URL.encodeToString(localPath(req.query("redirect")).getBytes(StandardCharsets.UTF_8))
                + "; Path=/auth/oidc; Max-Age=600; HttpOnly; SameSite=Lax" + (req.origin().startsWith("https") ? "; Secure" : ""));
        String challenge = BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        res.redirect(provider.authorizeUrl(callbackUri(req.origin(), provider.config.id()), state, nonce, challenge));
        return null;
    }

    private Object callback(Request req, Response res) {
        Provider provider = provider(req);
        String cookie = req.cookie(FLOW);
        String[] flow = cookie == null ? null : cookie.split("\\.");
        String state = req.query("state");
        res.header("Set-Cookie", FLOW + "=; Path=/auth/oidc; Max-Age=0");
        if (flow == null || flow.length != 4 || state == null
                || !MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII), flow[0].getBytes(StandardCharsets.US_ASCII))) {
            throw HttpException.badRequest("Sign-in state does not match; start again");
        }
        if (req.query("error") != null) throw HttpException.badRequest("The identity provider refused sign-in: " + req.query("error"));
        try {
            Map<String, Object> tokens = provider.token("grant_type=authorization_code&code=" + Provider.encode(req.query("code"))
                    + "&redirect_uri=" + Provider.encode(callbackUri(req.origin(), provider.config.id())) + "&code_verifier=" + flow[1]);
            String idToken = (String) tokens.get("id_token");
            Map<String, Object> claims = sessionClaims(provider.verifyIdToken(idToken), tokens);
            if (!flow[2].equals(claims.get("nonce"))) throw new IllegalStateException("nonce mismatch");
            String logout = provider.endSessionEndpoint == null ? null : provider.endSessionEndpoint
                    + (provider.endSessionEndpoint.indexOf('?') < 0 ? '?' : '&') + "client_id=" + Provider.encode(provider.config.clientId())
                    + "&id_token_hint=" + idToken + "&post_logout_redirect_uri=" + Provider.encode(req.origin() + "/");
            OidcPrincipal principal = new OidcPrincipal(provider.config.id(), (String) claims.get("sub"), claims,
                    (String) tokens.get("access_token"), (String) tokens.get("refresh_token"), logout);
            security.signIn(req, res, principal, expiry(tokens));
        } catch (Exception failed) {
            throw HttpException.badRequest("Sign-in could not be completed; start again");
        }
        res.redirect(localPath(new String(Base64.getUrlDecoder().decode(flow[3]), StandardCharsets.UTF_8)));
        return null;
    }

    private Session refresh(Session expired) {
        OidcPrincipal old = (OidcPrincipal) expired.principal();
        Provider provider = byId.get(old.provider());
        if (provider == null || old.refreshToken() == null) return null;
        try {
            Map<String, Object> tokens = provider.token("grant_type=refresh_token&refresh_token=" + Provider.encode(old.refreshToken()));
            Map<String, Object> claims = tokens.get("id_token") == null ? old.claims() : sessionClaims(provider.verifyIdToken(tokens.get("id_token")), tokens);
            if (!old.name().equals(claims.get("sub"))) return null;
            Object refreshToken = tokens.getOrDefault("refresh_token", old.refreshToken());
            return new Session(expired.id(), new OidcPrincipal(old.provider(), old.name(), claims,
                    (String) tokens.get("access_token"), (String) refreshToken, old.logoutUrl()), expiry(tokens));
        } catch (Exception failed) {
            return null;
        }
    }

    // -- Helpers ----------------------------------------------------------------

    private Provider provider(Request req) {
        Provider provider = byId.get(req.param("provider"));
        if (provider == null) throw HttpException.notFound("OIDC provider");
        return provider;
    }

    /** The ID token's claims plus the scopes the token response granted, which ID tokens do not carry. */
    private static Map<String, Object> sessionClaims(Map<String, Object> idClaims, Map<String, Object> tokens) {
        if (tokens.get("scope") == null) return idClaims;
        Map<String, Object> claims = new HashMap<>(idClaims);
        claims.put("scope", tokens.get("scope"));
        return claims;
    }

    private static Instant expiry(Map<String, Object> tokens) {
        return Instant.now().plusSeconds(tokens.get("expires_in") instanceof Number seconds ? seconds.longValue() : 300);
    }

    private static String callbackUri(String origin, String provider) {
        return origin + "/auth/oidc/" + provider + "/callback";
    }

    /** Only same-origin paths: anything else would make sign-in an open redirect. */
    private static String localPath(String path) {
        return path != null && path.startsWith("/") && !path.startsWith("//") && !path.startsWith("/\\") ? path : "/";
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return BASE64URL.encodeToString(value);
    }
}
