package dev.relism.flash.ext.oidc;

import dev.relism.flash.exceptions.HttpException;

/**
 * Thrown when OIDC token validation fails (signature, claims, nonce, expiry, etc.).
 * Distinct from {@link HttpException}: this signals a protocol-level
 * failure, not an HTTP response — callers decide the appropriate status code.
 */
public final class OidcValidationException extends RuntimeException {
    public OidcValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
