package dev.relism.ext.limiter;

import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashExtension;
import dev.relism.extension.FlashRegistrar;
import dev.relism.http.HttpStatus;
import dev.relism.routing.Middleware;

import java.util.List;

/**
 * Rate-limiting extension for Flash.
 *
 * <p>On {@link #install}:
 * <ol>
 *   <li>Creates a single {@link BucketStore} shared by all rules in this extension instance.</li>
 *   <li>Provides a {@link Guard} in the {@link FlashContext} for manual use on lambda routes.</li>
 *   <li>Registers an {@link dev.relism.extension.AnnotationProcessor} for {@link Limit}:
 *       reads the annotation once per handler class at boot, resolves the key lambda
 *       from the registry <em>fail-fast</em>, then returns a pre-compiled middleware
 *       that captures the lambda and config directly — zero map lookups at request time.</li>
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
 * LimiterConfig conf = new LimiterConfig()
 *     .registerResolver("auth_user", req -> ClaimsHolder.user().sub());
 *
 * app.install(new LimiterExtension(conf));
 *
 * Guard guard = app.ctx().require(Guard.class);
 * app.get("/api/search", handler).with(guard.limit("ip", 50, 1, TimeUnit.SECONDS));
 * }</pre>
 *
 * <h3>Installation order matters</h3>
 * Install {@code LimiterExtension} <em>before</em> authentication extensions so that
 * rate-limit checks short-circuit before expensive token validation on over-limit requests.
 */
public final class LimiterExtension implements FlashExtension {

    private final LimiterConfig config;

    /** Installs with default config (only the built-in {@code "ip"} resolver). */
    public LimiterExtension() {
        this(new LimiterConfig());
    }

    /** Installs with a custom {@link LimiterConfig} (custom resolvers, etc.). */
    public LimiterExtension(LimiterConfig config) {
        this.config = config;
    }

    @Override
    public void install(FlashRegistrar app, FlashContext ctx) {
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
     *   <li>Header values ({@code String.valueOf(...)}) are the only unavoidable allocations;
     *       they are tiny and bounded.</li>
     * </ul>
     */
    static Middleware buildMiddleware(KeyResolver resolver, LimitConfig cfg, BucketStore store) {
        // One result buffer per thread, per middleware instance.
        // ThreadLocal is captured in the closure at boot time — not re-created per request.
        ThreadLocal<long[]> resultBuf = ThreadLocal.withInitial(() -> new long[2]);

        return next -> (req, res) -> {
            String key    = resolver.resolve(req);
            Bucket bucket = store.get(key);
            long[] out    = resultBuf.get();

            boolean allowed = cfg.strategy().check(bucket, cfg, out);

            // Always inject rate-limit headers — useful even on allowed requests.
            res.header("X-RateLimit-Limit",     String.valueOf(cfg.limit()));
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
