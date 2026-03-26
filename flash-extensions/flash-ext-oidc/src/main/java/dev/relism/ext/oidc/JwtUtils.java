package dev.relism.ext.oidc;

import net.minidev.json.JSONValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Low-level JWT payload extraction — no signature or expiry validation.
 *
 * <p>Use only for tokens received directly from the provider over a trusted TLS
 * connection (e.g. {@code id_token} from the token endpoint). Bearer tokens on
 * incoming requests must go through {@link JwtValidator#validate(String)} instead.
 */
final class JwtUtils {

    private JwtUtils() {}

    /**
     * Base64URL-decodes the JWT payload and returns the claims as a map.
     * Signature, expiry, and issuer are NOT checked.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseClaims(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("Malformed JWT: " + jwt);
        // Pad to a multiple of 4 for the standard decoder
        String padded = parts[1];
        switch (padded.length() % 4) {
            case 2 -> padded += "==";
            case 3 -> padded += "=";
        }
        byte[] payload = Base64.getUrlDecoder().decode(padded);
        return (Map<String, Object>) JSONValue.parse(
                new String(payload, StandardCharsets.UTF_8));
    }
}
