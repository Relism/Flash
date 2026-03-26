package dev.relism.ext.oidc;

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
 * <pre>{@code
 * @Route(method = HttpMethod.GET, path = "/api/profile")
 * @Authenticated
 * public class GetProfile extends JacksonHandler { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Authenticated {
}
