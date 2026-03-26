package dev.relism.ext.oidc;

/**
 * Thrown when OIDC token validation fails (signature, claims, nonce, expiry, etc.).
 * Distinct from {@link dev.relism.exceptions.HttpException}: this signals a protocol-level
 * failure, not an HTTP response — callers decide the appropriate status code.
 */
public final class OidcValidationException extends RuntimeException {
    public OidcValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
