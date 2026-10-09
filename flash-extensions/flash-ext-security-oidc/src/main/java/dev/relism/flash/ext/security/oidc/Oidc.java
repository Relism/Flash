package dev.relism.flash.ext.security.oidc;

import com.nimbusds.jwt.SignedJWT;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.ext.security.AuthenticationFailedException;
import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.Redirects;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityScheme;
import dev.relism.flash.ext.security.Session;
import dev.relism.flash.ext.security.SessionRefresher;
import dev.relism.flash.ext.security.Tokens;
import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenID Connect for one app: bearer access tokens from any of its providers, and browser sign-in
 * through the authorization code flow with PKCE, which ends in a {@link Security} session renewed
 * with the refresh token. Installed by {@link OidcModule}; a handler that registers providers at
 * runtime takes it as a constructor parameter.
 *
 * <p>A bearer token is matched to its provider by {@code iss} before its signature is checked
 * against that provider's keys, so any number of providers costs one lookup; a token from an
 * issuer not configured here is left to other mechanisms.
 */
public final class Oidc implements AuthenticationMechanism, SessionRefresher, Bound {

    private static final String FLOW = "flash_oidc";
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final OidcProvider[] configured;
    private final Map<String, Provider> byId = new ConcurrentHashMap<>();
    private final Map<String, Provider> byIssuer = new ConcurrentHashMap<>();
    private Security security;
    /** Set by {@link OidcModule#allowLocalProviders()}, before the app starts. */
    boolean localProviders;

    Oidc(OidcProvider... providers) {
        this.configured = providers;
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
     * @param origin where users will sign in from, as {@link Security#origin(Request)} gives it
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

    /** Discovers every configured provider, with timeouts: an unreachable one fails the boot naming it, in seconds. */
    @Override
    public void bind(Services services) {
        security = services.get(Security.class);
        for (OidcProvider config : configured) {
            Provider provider = new Provider(config, false);
            byId.put(config.id(), provider);
            byIssuer.put(provider.issuer, provider);
        }
    }

    @Override
    public Class<? extends Principal> type() {
        return OidcPrincipal.class;
    }

    /** One per configured provider; one registered at runtime is the application's to name, never listed. */
    @Override
    public List<SecurityScheme> schemes() {
        return Arrays.stream(configured).map(config -> SecurityScheme.openIdConnect(config.id(), config.issuer())).toList();
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
            throw AuthenticationFailedException.invalidToken();
        }
        if (provider == null) return null;
        try {
            Map<String, Object> claims = provider.verifyAccessToken(jwt);
            return new OidcPrincipal(provider.config.id(), (String) claims.get("sub"), claims, token, null, null);
        } catch (Exception invalid) {
            throw AuthenticationFailedException.invalidToken();
        }
    }

    // -- Browser sign-in --------------------------------------------------------

    Object login(Request req, Response res) throws Exception {
        Provider provider = provider(req);
        String state = Tokens.random(16);
        String verifier = Tokens.random(32);
        String nonce = Tokens.random(16);
        res.header("Set-Cookie", FLOW + "=" + state + "." + verifier + "." + nonce + "."
                + BASE64URL.encodeToString(Redirects.localPath(req.query("redirect")).getBytes(StandardCharsets.UTF_8))
                + "; Path=/auth/oidc; Max-Age=600; HttpOnly; SameSite=Lax" + (security.origin(req).startsWith("https") ? "; Secure" : ""));
        String challenge = BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        res.redirect(provider.authorizeUrl(callbackUri(security.origin(req), provider.config.id()), state, nonce, challenge));
        return null;
    }

    Object callback(Request req, Response res) {
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
                    + "&redirect_uri=" + Provider.encode(callbackUri(security.origin(req), provider.config.id())) + "&code_verifier=" + flow[1]);
            String idToken = (String) tokens.get("id_token");
            Map<String, Object> claims = sessionClaims(provider.verifyIdToken(idToken), tokens);
            if (!flow[2].equals(claims.get("nonce"))) throw new IllegalStateException("nonce mismatch");
            String logout = provider.endSessionEndpoint == null ? null : provider.endSessionEndpoint
                    + (provider.endSessionEndpoint.indexOf('?') < 0 ? '?' : '&') + "client_id=" + Provider.encode(provider.config.clientId())
                    + "&id_token_hint=" + idToken + "&post_logout_redirect_uri=" + Provider.encode(security.origin(req) + "/");
            OidcPrincipal principal = new OidcPrincipal(provider.config.id(), (String) claims.get("sub"), claims,
                    (String) tokens.get("access_token"), (String) tokens.get("refresh_token"), logout);
            security.signIn(req, res, principal, expiry(tokens));
        } catch (Exception failed) {
            throw HttpException.badRequest("Sign-in could not be completed; start again");
        }
        res.redirect(Redirects.localPath(new String(Base64.getUrlDecoder().decode(flow[3]), StandardCharsets.UTF_8)));
        return null;
    }

    @Override
    public Session refresh(Session expired) {
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

}
