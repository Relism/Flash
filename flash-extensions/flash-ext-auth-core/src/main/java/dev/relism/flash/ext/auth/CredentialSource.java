package dev.relism.flash.ext.auth;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

import java.util.Map;

/**
 * Turns whatever a request carries — a bearer token, a session cookie, an API key — into the
 * claims authorization runs on. One is installed per authentication mechanism;
 * {@code flash-ext-auth-oidc} contributes the OpenID Connect one.
 *
 * <p>Implementations never touch {@link ClaimsHolder}: they produce claims and {@link
 * AuthMiddleware} publishes them for the duration of the request. Nothing outside this module can
 * inject claims into a request, which is the point.
 */
public interface CredentialSource {

    /**
     * Resolves the caller's claims, rejecting the request when it cannot.
     *
     * <p>Three outcomes, and the difference between the last two matters:
     * <ul>
     *   <li>claims — the caller presented a valid credential;</li>
     *   <li>{@code null} — no credential was presented and this source has already answered the
     *       request itself (typically a redirect into a sign-in flow). The middleware stops and
     *       writes nothing more;</li>
     *   <li>{@link HttpException} — a credential <em>was</em> presented and is invalid. The source
     *       sets any challenge header it owes the caller before throwing.</li>
     * </ul>
     */
    Map<String, Object> authenticate(Request req, Response res);

    /**
     * Resolves claims without ever rejecting: {@code null} when no valid credential is present.
     * Backs {@link Authenticated}{@code (optional = true)}, where an anonymous caller is a normal
     * outcome rather than a failure.
     */
    Map<String, Object> peek(Request req);

    /**
     * The {@code WWW-Authenticate} value to send with a 403 caused by missing scopes, or
     * {@code null} when this source has no such concept. Only consulted after authentication has
     * already succeeded.
     */
    default String insufficientScopeChallenge(String[] requiredScopes) { return null; }
}
