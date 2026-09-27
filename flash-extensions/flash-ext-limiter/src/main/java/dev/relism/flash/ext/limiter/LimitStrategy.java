package dev.relism.flash.ext.limiter;

import dev.relism.flash.ext.limiter.strategy.FixedWindowStrategy;
import dev.relism.flash.ext.limiter.strategy.SlidingWindowStrategy;
import dev.relism.flash.ext.limiter.strategy.TokenBucketStrategy;

/** The rate-limit algorithms, by name: {@code @Limit(requests = 100, window = 1, strategy = TOKEN_BUCKET)}. */
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
