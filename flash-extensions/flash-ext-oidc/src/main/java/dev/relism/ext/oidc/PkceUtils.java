package dev.relism.ext.oidc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (RFC 7636) utilities: code verifier generation and S256 challenge computation.
 * Package-private — used exclusively by {@link OidcExtension}.
 */
final class PkceUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PkceUtils() {}

    /**
     * Generates a cryptographically random code verifier (43 URL-safe characters,
     * per RFC 7636 §4.1 — 32 bytes encoded as unpadded Base64URL).
     */
    static String generateVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the S256 code challenge: {@code BASE64URL(SHA-256(ASCII(verifier)))}.
     */
    static String computeChallenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
