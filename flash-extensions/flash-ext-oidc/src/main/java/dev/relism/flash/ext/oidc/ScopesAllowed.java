package dev.relism.flash.ext.oidc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a handler to callers whose token carries the required OAuth2 scopes.
 * Authentication is implicitly required.
 *
 * <p>Scopes are resolved from the configured claim paths in
 * {@link OidcConfig#scopeClaimPaths()} (default: {@code "scope,scp"}) and support
 * both standard formats:
 * <ul>
 *   <li>{@code scope}: space-separated string</li>
 *   <li>{@code scp}: string list (or string)</li>
 * </ul>
 *
 * <pre>{@code
 * @Route(method = HttpMethod.GET, path = "/api/orders")
 * @ScopesAllowed("orders:read")
 * public class ListOrders extends JacksonHandler { ... }
 *
 * @Route(method = HttpMethod.POST, path = "/api/orders")
 * @ScopesAllowed(value = {"orders:write", "payments:write"}, match = ScopesAllowed.Match.ANY)
 * public class CreateOrder extends JacksonHandler { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ScopesAllowed {
    /** Required scopes. */
    String[] value();

    /** Matching mode for {@link #value()}. */
    Match match() default Match.ALL;

    enum Match {
        /** Any one required scope is sufficient. */
        ANY,
        /** All required scopes must be present. */
        ALL
    }
}
