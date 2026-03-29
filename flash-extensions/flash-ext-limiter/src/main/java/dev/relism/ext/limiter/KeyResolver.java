package dev.relism.ext.limiter;

import dev.relism.models.Request;

/**
 * Extracts a partition key from an incoming request.
 *
 * <p>The resolved key identifies who the rate limit applies to — an IP address,
 * an authenticated user ID, an API key, etc. Implementations are captured once
 * at route registration time and called directly (no registry lookup) on every request.
 *
 * <pre>{@code
 * conf.registerResolver("ip",       req -> req.header("X-Forwarded-For"));
 * conf.registerResolver("auth_user", req -> ClaimsHolder.user().sub());
 * }</pre>
 */
@FunctionalInterface
public interface KeyResolver {
    String resolve(Request req);
}
