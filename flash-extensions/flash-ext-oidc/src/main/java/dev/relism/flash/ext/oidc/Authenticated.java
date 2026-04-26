package dev.relism.flash.ext.oidc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler as requiring a valid JWT. Any bearer token that passes
 * signature + expiry + issuer validation is accepted — no role check is performed.
 *
 * <p>For role-based access use {@link RolesAllowed} instead (it implies authentication).
 *
 * <p>Set {@code optional = true} on public routes that personalise their response when
 * the user happens to be logged in but should remain accessible to guests. The middleware
 * will populate {@link ClaimsHolder} if credentials are present and silently skip it
 * otherwise — the request is never rejected.
 *
 * <pre>{@code
 * // Hard auth — redirects / 401 when unauthenticated:
 * @Route(method = HttpMethod.GET, path = "/api/profile")
 * @Authenticated
 * public class GetProfile extends JacksonHandler { ... }
 *
 * // Soft auth — guest-friendly, ClaimsHolder populated only when logged in:
 * @Route(method = HttpMethod.GET, path = "/")
 * @Authenticated(optional = true)
 * public class HomePage extends HtmlHandler { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Authenticated {
    /**
     * When {@code true} the middleware never rejects unauthenticated requests — it only
     * populates {@link ClaimsHolder} when valid credentials are present.
     * Defaults to {@code false} (hard authentication required).
     */
    boolean optional() default false;
}
