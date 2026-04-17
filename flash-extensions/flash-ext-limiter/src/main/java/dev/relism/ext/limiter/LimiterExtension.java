package dev.relism.ext.limiter;

import dev.relism.extension.ExtensionPhase;
import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashExtension;
import dev.relism.http.HttpStatus;
import dev.relism.routing.Middleware;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Rate-limiting extension for Flash.
 *
 * <p>At {@link #provide}:
 * <ol>
 *   <li>Creates a single {@link BucketStore} shared by all rules in this extension instance.</li>
 *   <li>Provides a {@link Guard} in the {@link FlashContext} for manual use on lambda routes.</li>
 *   <li>Registers an {@link dev.relism.extension.AnnotationProcessor} for {@link Limit}:
 *       reads the annotation once per handler class at boot, resolves the key lambda
 *       fail-fast, then returns a pre-compiled middleware — zero map lookups at request time.</li>
 * </ol>
 *
 * <h3>Annotation-based (class handlers)</h3>
 * <pre>{@code
 * @Limit(requests = 100, window = 1)                          // 100 req/s per IP
 * public class SearchHandler extends RequestHandler { ... }
 *
 * @Limit(key = "auth_user", requests = 20, window = 1,
 *        windowUnit = TimeUnit.MINUTES,
 *        strategy   = LimitStrategy.TOKEN_BUCKET)
 * public class ReportHandler extends RequestHandler { ... }
 * }</pre>
 *
 * <h3>Lambda routes (via Guard)</h3>
 * <pre>{@code
 * app.install(new LimiterExtension(
 *     new LimiterConfig().registerResolver("auth_user", req -> ClaimsHolder.user().sub())));
 *
 * // inside FlashExtension.routes() or after install():
 * Guard guard = ctx.require(Guard.class);
 * app.get("/api/search", handler, guard.limit("ip", 50, 1, TimeUnit.SECONDS));
 * }</pre>
 */
public final class LimiterExtension implements FlashExtension {

    private final LimiterConfig config;

    /**
     * Rate limiting runs before authentication — cheaper check rejects over-limit
     * requests before any token validation occurs.
     */
    @Override public int priority() { return ExtensionPhase.EARLY.value; }

    /** Installs with default config (only the built-in {@code "ip"} resolver). */
    public LimiterExtension() {
        this(new LimiterConfig());
    }

    /** Installs with a custom {@link LimiterConfig} (custom resolvers, etc.). */
    public LimiterExtension(LimiterConfig config) {
        this.config = config;
    }

    @Override
    public void provide(FlashContext ctx) {
        BucketStore store = new BucketStore();
        Guard       guard = new Guard(config, store);

        ctx.provide(Guard.class,        guard);
        ctx.provide(LimiterConfig.class, config);

        // Annotation processor: runs once per class-based handler at boot.
        ctx.addAnnotationProcessor(handlerClass -> {
            Limit ann = handlerClass.getAnnotation(Limit.class);
            if (ann == null) return List.of();

            // Fail-fast: if the key is unknown the server refuses to start.
            KeyResolver resolver = config.requireResolver(ann.key());
            LimitConfig cfg      = new LimitConfig(
                    ann.requests(),
                    ann.windowUnit().toMillis(ann.window()),
                    ann.strategy().create()
            );

            return List.of(buildMiddleware(resolver, cfg, store));
        });
    }

    // ── Package-private helper — shared with Guard ────────────────────────────

    /**
     * Builds the rate-limit {@link Middleware} from an already-resolved resolver lambda.
     *
     * <p>Hot-path design:
     * <ul>
     *   <li>{@code resolver} is captured directly in the closure — no registry lookup per request.</li>
     *   <li>{@code resultBuf} is a per-{@link Middleware}-instance ThreadLocal {@code long[2]}.
     *       Allocated once per thread, reused forever — zero per-request allocation.</li>
     *   <li>The static {@code X-RateLimit-Limit} header is pre-encoded at boot — zero-alloc.</li>
     * </ul>
     */
    static Middleware buildMiddleware(KeyResolver resolver, LimitConfig cfg, BucketStore store) {
        ThreadLocal<long[]> resultBuf  = ThreadLocal.withInitial(() -> new long[2]);
        byte[]              limitHeader = ("X-RateLimit-Limit: " + cfg.limit() + "\r\n")
                .getBytes(StandardCharsets.UTF_8);

        return next -> (req, res) -> {
            String key    = resolver.resolve(req);
            Bucket bucket = store.get(key);
            long[] out    = resultBuf.get();

            boolean allowed = cfg.strategy().check(bucket, cfg, out);

            res.header(limitHeader);
            res.header("X-RateLimit-Remaining", String.valueOf(out[0]));
            res.header("X-RateLimit-Reset",     String.valueOf(out[1]));

            if (!allowed) {
                long retryAfter = Math.max(1L, out[1] - System.currentTimeMillis() / 1000L);
                res.status(HttpStatus.TOO_MANY_REQUESTS)
                   .header("Retry-After", String.valueOf(retryAfter));
                return "Too Many Requests";
            }

            return next.handle(req, res);
        };
    }
}
