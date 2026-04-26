package dev.relism.flash.ext.limiter;

import dev.relism.flash.ext.limiter.strategy.FixedWindowStrategy;
import dev.relism.flash.ext.limiter.strategy.SlidingWindowStrategy;
import dev.relism.flash.ext.limiter.strategy.TokenBucketStrategy;

/**
 * Enumeration of built-in rate-limit algorithms. Each constant is a factory
 * for its corresponding {@link RateLimitStrategy} implementation.
 *
 * <p>New algorithms can be added here without touching the rest of the extension.
 * The enum value is referenced by {@link Limit#strategy()} so user code refers
 * to the algorithm by name ({@code LimitStrategy.FIXED_WINDOW}) rather than
 * instantiating strategy objects directly.
 *
 * <pre>{@code
 * @Limit(key = "ip", requests = 100, window = 1, strategy = LimitStrategy.TOKEN_BUCKET)
 * public class SearchHandler extends RequestHandler { ... }
 * }</pre>
 */
public enum LimitStrategy {

    /**
     * Fixed-window counter: resets to zero at each clock-aligned window boundary.
     * Simple, minimal memory, but allows up to 2× the limit in bursts that straddle
     * two windows.
     */
    FIXED_WINDOW {
        @Override
        public RateLimitStrategy create() { return new FixedWindowStrategy(); }
    },

    /**
     * Token-bucket: tokens refill continuously. Smooth burst absorption — a client
     * that was idle accumulates tokens and can fire a short burst, but sustained
     * excess traffic is rejected. Preferred for API endpoints where occasional bursts
     * are legitimate.
     */
    TOKEN_BUCKET {
        @Override
        public RateLimitStrategy create() { return new TokenBucketStrategy(); }
    },

    /**
     * Sliding-window counter: interpolates between the previous window's count and the
     * current window's count weighted by how far into the current window we are.
     * Eliminates the boundary burst of {@link #FIXED_WINDOW} while remaining O(1)
     * memory and lock-free. Slight approximation — worst-case error ≈ a few percent at
     * window boundaries.
     */
    SLIDING_WINDOW {
        @Override
        public RateLimitStrategy create() { return new SlidingWindowStrategy(); }
    };

    /** Creates a fresh, stateless {@link RateLimitStrategy} instance for this algorithm. */
    public abstract RateLimitStrategy create();
}
