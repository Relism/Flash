package dev.relism.flash.ext.limiter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Rate-limits a handler class, read once at boot by {@link LimiterModule}. A {@link #key()} no
 * resolver answers to fails the boot.
 *
 * <pre>{@code
 * // 100 req/s per client IP — fixed window
 * @Limit(requests = 100, window = 1)
 * public class SearchHandler extends RequestHandler { ... }
 *
 * // 20 req/min per authenticated user — token bucket
 * @Limit(key = "auth_user", requests = 20, window = 1, windowUnit = TimeUnit.MINUTES,
 *        strategy = LimitStrategy.TOKEN_BUCKET)
 * public class ExpensiveHandler extends RequestHandler { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Limit {

    /** Name of the resolver registered via {@link LimiterModule#resolver}. Default: {@code "ip"}. */
    String key() default "ip";

    /** Maximum number of requests allowed per {@link #window()}. */
    int requests();

    /** Window duration in {@link #windowUnit()} units. */
    long window();

    /** Unit for {@link #window()}. Default: {@link TimeUnit#SECONDS}. */
    TimeUnit windowUnit() default TimeUnit.SECONDS;

    /** Rate-limit algorithm. Default: {@link LimitStrategy#FIXED_WINDOW}. */
    LimitStrategy strategy() default LimitStrategy.FIXED_WINDOW;
}
