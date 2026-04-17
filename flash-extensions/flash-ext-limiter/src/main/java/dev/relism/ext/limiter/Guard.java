package dev.relism.ext.limiter;

import dev.relism.routing.Middleware;

import java.util.concurrent.TimeUnit;

/**
 * Manual rate-limit guard for lambda routes.
 *
 * <p>Available via {@link dev.relism.extension.FlashContext}:
 * <pre>{@code
 * Guard guard = ctx.require(Guard.class);
 * }</pre>
 *
 * <p>{@link #limit} creates a {@link Middleware} that is composed once at route registration
 * time — the resolver lambda is captured directly from the registry (no runtime map lookup):
 * <pre>{@code
 * // 50 req/s per IP — fixed window (default)
 * app.get("/api/search", handler, guard.limit("ip", 50, 1, TimeUnit.SECONDS));
 *
 * // 10 req/min per authenticated user — token bucket
 * app.post("/api/export", handler, guard.limit("auth_user", 10, 1, TimeUnit.MINUTES, LimitStrategy.TOKEN_BUCKET));
 * }</pre>
 *
 * <p>The resolver name is looked up once here (at wiring time, not on each request).
 * If the name is not registered, {@link dev.relism.exceptions.InitializationException}
 * is thrown immediately.
 */
public final class Guard {

    private final LimiterConfig config;
    private final BucketStore   store;

    Guard(LimiterConfig config, BucketStore store) {
        this.config = config;
        this.store  = store;
    }

    /**
     * Returns a {@link Middleware} that enforces the given rate limit using
     * {@link LimitStrategy#FIXED_WINDOW}.
     *
     * @param resolverKey name registered via {@link LimiterConfig#registerResolver}
     * @param requests    maximum requests allowed per window
     * @param window      window duration in {@code unit}
     * @param unit        time unit for {@code window}
     */
    public Middleware limit(String resolverKey, int requests, long window, TimeUnit unit) {
        return limit(resolverKey, requests, window, unit, LimitStrategy.FIXED_WINDOW);
    }

    /**
     * Returns a {@link Middleware} that enforces the given rate limit with the specified strategy.
     *
     * @param resolverKey name registered via {@link LimiterConfig#registerResolver}
     * @param requests    maximum requests allowed per window
     * @param window      window duration in {@code unit}
     * @param unit        time unit for {@code window}
     * @param strategy    rate-limit algorithm
     */
    public Middleware limit(String resolverKey, int requests, long window, TimeUnit unit, LimitStrategy strategy) {
        // Fail-fast: resolve the lambda at wiring time, not at request time.
        KeyResolver resolver = config.requireResolver(resolverKey);
        LimitConfig cfg      = new LimitConfig(requests, unit.toMillis(window), strategy.create());
        return LimiterExtension.buildMiddleware(resolver, cfg, store);
    }
}
