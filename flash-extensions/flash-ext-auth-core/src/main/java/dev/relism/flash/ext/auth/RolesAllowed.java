package dev.relism.flash.ext.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a handler to callers holding at least one of the named roles. Authentication is
 * implied — there is no need to combine it with {@link Authenticated}.
 *
 * <p>Roles are read from the claim path the installed credential source is configured with
 * (Keycloak's is {@code realm_access.roles}; many providers use a flat {@code roles} or
 * {@code groups}). Nested paths use dot notation.
 *
 * <pre>{@code
 * @Route(method = HttpMethod.DELETE, path = "/api/admin/blogs/{id}")
 * @RolesAllowed("admin")
 * public class DeleteBlog extends JacksonHandler { ... }
 *
 * // Multiple accepted roles (OR semantics — any one is sufficient):
 * @RolesAllowed({"admin", "editor"})
 * public class UpdateBlog extends JacksonHandler { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RolesAllowed {
    /** One or more role names. Access is granted if the caller has any of them. */
    String[] value();
}
