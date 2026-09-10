package dev.relism.flash.ext.oidc;

/**
 * OAuth2 client authentication method for the token endpoint (RFC 6749 §2.3).
 *
 * <ul>
 *   <li>{@link #POST} — credentials sent as {@code client_id} / {@code client_secret}
 *       form fields (default; most providers).</li>
 *   <li>{@link #BASIC} — credentials sent as an {@code Authorization: Basic} header;
 *       body contains only grant-specific parameters.</li>
 * </ul>
 */
public enum ClientAuthMethod {
    /** {@code client_secret_post} — credentials in the request body. */
    POST,
    /** {@code client_secret_basic} — credentials in the {@code Authorization} header. */
    BASIC
}
