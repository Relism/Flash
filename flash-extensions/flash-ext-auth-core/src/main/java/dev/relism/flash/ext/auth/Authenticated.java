package dev.relism.flash.ext.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler as requiring an authenticated caller. Any credential a registered source
 * accepts is enough — no role or scope check is performed.
 *
 * <p>For role-based access use {@link RolesAllowed} instead (it implies authentication).
 *
 * <p>Set {@code optional = true} on public routes that personalise their response when the caller
 * happens to be signed in but should remain reachable by guests. The middleware populates
 * {@link ClaimsHolder} when a credential is present and silently skips it otherwise — the request
 * is never rejected.
 *
 * <pre>{@code
 * // Hard auth — 401 or a redirect when unauthenticated:
 * @Route(method = HttpMethod.GET, path = "/api/profile")
 * @Authenticated
 * public class GetProfile extends JacksonHandler { ... }
 *
 * // Soft auth — guest-friendly, ClaimsHolder populated only when signed in:
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
