package dev.relism.flash.ext.security.oidc;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One discovered provider: its endpoints, its signing keys, and the two token verifiers they back. */
final class Provider {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    final OidcProvider config;
    final String issuer;
    final String authorizationEndpoint;
    final String endSessionEndpoint;
    private final String tokenEndpoint;
    private final String clientAuthorization;
    private final DefaultJWTProcessor<SecurityContext> accessTokens;
    private final DefaultJWTProcessor<SecurityContext> idTokens;

    /**
     * Fetches discovery. {@code guarded} providers — registered at runtime, from input the operator does
     * not control — must be https on public addresses, and so must every endpoint their discovery names:
     * otherwise registering one is a request forgery against the server's own network.
     */
    Provider(OidcProvider config, boolean guarded) {
        this.config = config;
        try {
            if (guarded) requirePublic(config.discoveryUrl());
            Map<String, Object> discovery = JSONObjectUtils.parse(HTTP.send(HttpRequest.newBuilder(URI.create(config.discoveryUrl())).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            if (guarded) for (String key : new String[]{"authorization_endpoint", "token_endpoint", "jwks_uri"}) requirePublic((String) discovery.get(key));
            issuer = (String) discovery.get("issuer");
            authorizationEndpoint = (String) discovery.get("authorization_endpoint");
            tokenEndpoint = (String) discovery.get("token_endpoint");
            endSessionEndpoint = (String) discovery.get("end_session_endpoint");
            JWKSource<SecurityContext> keys = JWKSourceBuilder.create(URI.create((String) discovery.get("jwks_uri")).toURL()).retrying(true).build();
            accessTokens = processor(keys, null, new JOSEObjectType("at+jwt"));
            idTokens = processor(keys, config.clientId(), null);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException rejected) throw rejected;
            throw new IllegalStateException("OIDC discovery failed for " + config.discoveryUrl(), e);
        }
        if (issuer == null || authorizationEndpoint == null || tokenEndpoint == null) {
            throw new IllegalStateException("Incomplete OIDC discovery document at " + config.discoveryUrl());
        }
        clientAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                (encode(config.clientId()) + ":" + encode(config.clientSecret())).getBytes(StandardCharsets.UTF_8));
    }

    Map<String, Object> verifyAccessToken(SignedJWT token) throws Exception {
        return accessTokens.process(token, null).getClaims();
    }

    Map<String, Object> verifyIdToken(Object idToken) throws Exception {
        if (!(idToken instanceof String jwt)) throw new IllegalStateException("The provider returned no ID token");
        return idTokens.process(jwt, null).getClaims();
    }

    /** The sign-in request. {@link #verify} sends this same one, so it is refused exactly when a real sign-in would be. */
    String authorizeUrl(String redirectUri, String state, String nonce, String challenge) {
        return authorizationEndpoint + (authorizationEndpoint.indexOf('?') < 0 ? '?' : '&')
                + "response_type=code&client_id=" + encode(config.clientId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(config.scopes())
                + "&state=" + state + "&nonce=" + nonce + "&code_challenge=" + challenge + "&code_challenge_method=S256";
    }

    /** A token endpoint call, authenticated as the client ({@code client_secret_basic}). */
    Map<String, Object> token(String form) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", clientAuthorization)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Token endpoint answered " + response.statusCode());
        return JSONObjectUtils.parse(response.body());
    }

    /**
     * Throws naming what the provider refuses, without anyone signing in. Client authentication is
     * checked before the grant (RFC 6749 §5.2), so a made-up code tells a bad secret (401) from a good
     * one. A redirect URI the client has not registered is answered with an error page, never a
     * redirect (§4.1.2.1), but not always on the first hop: some providers bounce to their login page
     * first, so same-host redirects are followed.
     */
    void verify(String redirectUri) {
        try {
            HttpResponse<String> token = HTTP.send(HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", clientAuthorization)
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code&code=verify&redirect_uri=" + encode(redirectUri)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            String error = token.body().contains("\"error\"") ? (String) JSONObjectUtils.parse(token.body()).get("error") : null;
            if (token.statusCode() == 401 || "invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                throw new IllegalArgumentException("The provider rejected the client ID or secret");
            }
            // RFC 7636's example challenge: well-formed, so a provider that insists on PKCE judges the rest.
            URI hop = URI.create(authorizeUrl(redirectUri, "verify", "verify", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"));
            String authority = hop.getAuthority();
            for (int i = 0; i < 5; i++) {
                HttpResponse<Void> answer = HTTP.send(HttpRequest.newBuilder(hop).build(), HttpResponse.BodyHandlers.discarding());
                if (answer.statusCode() >= 400) throw new IllegalArgumentException("The provider does not accept the redirect URI " + redirectUri);
                String location = answer.headers().firstValue("Location").orElse(null);
                if (answer.statusCode() / 100 != 3 || location == null) return;
                hop = hop.resolve(location);
                // Back to the client, or onwards to a login somewhere else: either way it was accepted. Authority,
                // not host: in development the provider and the client share localhost on different ports.
                if (hop.toString().startsWith(redirectUri) || !authority.equals(hop.getAuthority())) return;
            }
        } catch (IllegalArgumentException refused) {
            throw refused;
        } catch (Exception unreachable) {
            throw new IllegalStateException("Could not reach " + config.issuer(), unreachable);
        }
    }

    /** ponytail: resolved once here and again by the HTTP client, so DNS rebinding between the two is not covered. */
    private static void requirePublic(String url) throws Exception {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("Not https: " + url);
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress() || (address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc)) {
                throw new IllegalArgumentException("Not a public address: " + url);
            }
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private DefaultJWTProcessor<SecurityContext> processor(JWKSource<SecurityContext> keys, String audience, JOSEObjectType type) {
        Set<JWSAlgorithm> algorithms = new HashSet<>(JWSAlgorithm.Family.RSA);
        algorithms.addAll(JWSAlgorithm.Family.EC);
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keys));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(audience, new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("sub", "exp")));
        if (type != null) processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, type, null));
        return processor;
    }
}
