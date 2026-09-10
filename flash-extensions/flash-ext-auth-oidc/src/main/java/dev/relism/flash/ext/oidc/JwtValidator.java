package dev.relism.flash.ext.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.relism.flash.exceptions.HttpException;

import java.io.IOException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;

/**
 * Validates JWTs against a remote JWKS endpoint using Nimbus JOSE+JWT.
 *
 * <p>Two validation modes:
 * <ul>
 *   <li>{@link #validate(String)} — access token bearer validation per request (hot path).
 *       Checks signature, {@code iss}, {@code exp}, {@code iat}, {@code sub}.
 *       Throws {@link HttpException} 401 so the middleware can short-circuit.</li>
 *   <li>{@link #validateIdToken(String, String)} — ID token validation at callback time.
 *       Checks signature, {@code iss}, {@code aud} == clientId, {@code exp}, {@code iat},
 *       {@code sub}, and {@code nonce} (if provided).
 *       Throws {@link OidcValidationException} (not 401 — it is a provider/protocol error).</li>
 * </ul>
 *
 * <p>JWKS handling: the shared {@link JWKSource} uses caching + rate-limiting + automatic
 * retry-on-key-miss (key rotation). Both processors share the same source — one JWKS
 * fetch serves both token types.
 */
public class JwtValidator {

    private final JWKSource<SecurityContext>                 jwkSource;
    private final ConfigurableJWTProcessor<SecurityContext>  accessTokenProcessor;
    private final ConfigurableJWTProcessor<SecurityContext>  idTokenProcessor;
    private final String                                     algorithm;

    /**
     * @param jwksUri   JWKS endpoint URI
     * @param issuer    Expected {@code iss} claim
     * @param clientId  OAuth2 client ID — used as expected {@code aud} in ID tokens
     * @param algorithm JWS algorithm (e.g. {@code "RS256"})
     * @param http      Shared {@link HttpClient} used for all JWKS fetches — already configured
     *                  with the correct TLS policy (trust-all or default trust store).
     */
    public JwtValidator(String jwksUri, String issuer, String clientId,
                        String algorithm, HttpClient http) {
        try {
            // Use the caller-supplied HttpClient for JWKS retrieval so that TLS policy
            // (insecureTls / custom trust store) is applied consistently everywhere.
            this.jwkSource = JWKSourceBuilder
                    .create(new URL(jwksUri), httpRetriever(http))
                    .cache(true)
                    .rateLimited(true)
                    .retrying(true)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init JWKS source: " + jwksUri, e);
        }
        this.algorithm            = algorithm;
        this.accessTokenProcessor = buildAccessTokenProcessor(jwkSource, issuer, algorithm);
        this.idTokenProcessor     = buildIdTokenProcessor(jwkSource, issuer, clientId, algorithm);
    }

    // -- Public API -----------------------------------------------------------

    /**
     * Validates a JWT access token (bearer on incoming request).
     * Returns claims on success; throws {@link HttpException} 401 on any failure.
     */
    public Map<String, Object> validate(String token) {
        if (!isJwt(token)) throw HttpException.unauthorized();   // opaque token — can't validate
        try {
            return accessTokenProcessor.process(token, null).getClaims();
        } catch (Exception e) {
            throw HttpException.unauthorized();
        }
    }

    /**
     * Validates an ID token received directly from the token endpoint.
     *
     * <p>Checks: signature (JWKS), {@code iss}, {@code aud} == clientId,
     * {@code exp}, {@code iat}, {@code sub}, and {@code nonce} if provided.
     *
     * @param idToken  Raw ID token string
     * @param nonce    Nonce sent in the authorization request; {@code null} to skip check
     * @throws OidcValidationException on any validation failure
     */
    public Map<String, Object> validateIdToken(String idToken, String nonce) {
        try {
            Map<String, Object> claims = idTokenProcessor.process(idToken, null).getClaims();
            if (nonce != null && !nonce.equals(claims.get("nonce")))
                throw new OidcValidationException("ID token nonce mismatch", null);
            return claims;
        } catch (OidcValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcValidationException("ID token validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns {@code true} if {@code token} is a signed JWT (three dot-separated Base64URL parts).
     * Used to detect opaque access tokens before attempting JWKS validation.
     */
    public static boolean isJwt(String token) {
        if (token == null || token.isBlank()) return false;
        int dots = 0;
        for (int i = 0; i < token.length(); i++) if (token.charAt(i) == '.') dots++;
        return dots == 2;
    }

    // -- Processors -----------------------------------------------------------

    private static ConfigurableJWTProcessor<SecurityContext> buildAccessTokenProcessor(
            JWKSource<SecurityContext> src, String issuer, String algorithm) {

        ConfigurableJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(keySelector(src, algorithm));
        // iss required; aud not enforced on ATs (varies by provider)
        if (issuer != null && !issuer.isBlank()) {
            p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    new JWTClaimsSet.Builder().issuer(issuer).build(),
                    Set.of("sub", "iat", "exp")));
        }
        return p;
    }

    private static ConfigurableJWTProcessor<SecurityContext> buildIdTokenProcessor(
            JWKSource<SecurityContext> src, String issuer, String clientId, String algorithm) {

        ConfigurableJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(keySelector(src, algorithm));
        // iss + aud = clientId strictly required (OIDC Core §3.1.3.7)
        JWTClaimsSet.Builder required = new JWTClaimsSet.Builder();
        if (issuer   != null) required.issuer(issuer);
        if (clientId != null) required.audience(clientId);
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                required.build(), Set.of("sub", "iat", "exp")));
        return p;
    }

    private static JWSKeySelector<SecurityContext> keySelector(
            JWKSource<SecurityContext> src, String algorithm) {
        return new JWSVerificationKeySelector<>(JWSAlgorithm.parse(algorithm), src);
    }

    /**
     * Wraps a {@link HttpClient} as a Nimbus {@link ResourceRetriever}.
     * The client already carries the correct TLS policy (trust-all or default),
     * so JWKS fetches honour the same SSL configuration as discovery and token requests.
     */
    private static ResourceRetriever httpRetriever(HttpClient http) {
        return url -> {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder().uri(url.toURI()).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200)
                    throw new IOException("JWKS fetch failed [" + resp.statusCode() + "]: " + url);
                String contentType = resp.headers()
                        .firstValue("Content-Type").orElse("application/json");
                return new Resource(resp.body(), contentType);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("JWKS retrieval error: " + e.getMessage(), e);
            }
        };
    }
}
