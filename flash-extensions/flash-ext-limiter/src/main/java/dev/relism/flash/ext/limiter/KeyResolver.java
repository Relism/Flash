package dev.relism.flash.ext.limiter;

import dev.relism.flash.models.Request;

/**
 * Extracts a partition key from an incoming request.
 *
 * <p>The resolved key identifies who the rate limit applies to — an IP address,
 * an authenticated user ID, an API key, etc. Implementations are captured once
 * at route registration time and called directly (no registry lookup) on every request.
 *
 * <pre>{@code
 * conf.registerResolver("ip",       req -> req.header("X-Forwarded-For"));
 * conf.registerResolver("auth_user", req -> SecurityIdentity.current().principal().name());
 * }</pre>
 */
@FunctionalInterface
public interface KeyResolver {
    String resolve(Request req);
}
