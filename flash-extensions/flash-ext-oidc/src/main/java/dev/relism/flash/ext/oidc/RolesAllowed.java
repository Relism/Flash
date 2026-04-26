package dev.relism.flash.ext.oidc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a handler to callers whose JWT contains at least one of the
 * specified roles. Authentication is implicitly required — no need to combine
 * with {@link Authenticated}.
 *
 * <p>Roles are read from the claim configured in {@link OidcConfig#rolesClaimPath()}
 * (default: {@code "roles"}). Nested paths like {@code "realm_access.roles"} are
 * supported with dot notation.
 *
 * <pre>{@code
 * @Route(method = HttpMethod.DELETE, path = "/api/admin/blogs/{id}")
 * @RolesAllowed("admin")
 * public class DeleteBlog extends JacksonHandler { ... }
 *
 * // Multiple accepted roles (OR semantics — any one role is sufficient):
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
