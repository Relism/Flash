package dev.relism.flash.ext.security;

import dev.relism.flash.models.Request;

import java.util.List;

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

    /** How OpenAPI documents each credential this mechanism reads, and how a 401 challenges for it. */
    default List<SecurityScheme> schemes() { return List.of(); }
}
