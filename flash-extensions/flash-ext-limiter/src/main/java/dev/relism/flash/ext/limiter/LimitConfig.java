package dev.relism.flash.ext.limiter;

/**
 * Immutable configuration snapshot for a single rate-limit rule.
 * Created once at boot time and captured directly in the middleware closure.
 *
 * @param limit      Maximum allowed requests per window.
 * @param windowMs   Window duration in milliseconds.
 * @param strategy   Strategy instance bound to this rule (one per rule, not shared).
 */
public record LimitConfig(int limit, long windowMs, RateLimitStrategy strategy) {}
