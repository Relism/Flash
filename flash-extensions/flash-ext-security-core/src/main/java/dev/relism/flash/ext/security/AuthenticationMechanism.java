package dev.relism.flash.ext.security;

import dev.relism.flash.models.Request;

/**
 * Reads one kind of credential off a request. A mechanism never writes the response: an anonymous
 * request is answered by the {@link AuthenticationEntryPoint}, a rejected one by the 401 its
 * {@link AuthenticationFailedException} carries.
 */
public interface AuthenticationMechanism {

    /**
     * The caller, or {@code null} when the request carries no credential of this kind.
     *
     * @throws AuthenticationFailedException the request carries one, and it is invalid
     */
    Principal authenticate(Request req);

    /** How OpenAPI documents the credential and a 401 challenges for it; {@code null} for neither. */
    default SecurityScheme scheme() { return null; }
}
