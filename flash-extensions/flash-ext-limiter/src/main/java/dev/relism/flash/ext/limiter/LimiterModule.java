package dev.relism.flash.ext.limiter;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.ext.openapi.OpenApiResponseContribution;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.PreEncodedHeader;
import dev.relism.flash.http.Request;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.MiddlewareKey;
import dev.relism.flash.routing.MiddlewareNode;
import dev.relism.flash.routing.RouteHook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dev.relism.flash.ext.limiter.RateLimitStrategy.allowed;
import static dev.relism.flash.ext.limiter.RateLimitStrategy.remaining;
import static dev.relism.flash.ext.limiter.RateLimitStrategy.reset;

/**
 * Rate limits: {@link Limit} on a handler class, or {@link #limit} as middleware on any route. Each
 * rule counts in its own buckets, by the key its resolver gives; {@code "ip"} is built in and reads
 * {@link Request#clientAddress()}. An unknown key fails at boot.
 *
 * <pre>{@code
 * LimiterModule limiter = new LimiterModule().resolver("user", req -> identity(req));
 * app.install(limiter)
 *    .get("/search", search, limiter.limit("ip", 50, 1, TimeUnit.SECONDS));
 * }</pre>
 */
public final class LimiterModule implements Module {

    private static final MiddlewareKey LIMIT = MiddlewareKey.of("flash.limiter.limit");

    private final Map<String, KeyResolver> resolvers = new HashMap<>(Map.of("ip", Request::clientAddress));
    private int maxKeys = 100_000;

    /** Names a way to tell clients apart, for {@link Limit#key()} and {@link #limit}. */
    public LimiterModule resolver(String name, KeyResolver resolver) {
        resolvers.put(name, resolver);
        return this;
    }

    /** The most keys one rule tracks before new ones share a single bucket; 100 000 by default. */
    public LimiterModule maxKeys(int maxKeys) {
        this.maxKeys = maxKeys;
        return this;
    }

    public Middleware limit(String key, int requests, long window, TimeUnit unit) {
        return limit(key, requests, window, unit, LimitStrategy.FIXED_WINDOW);
    }

    public Middleware limit(String key, int requests, long window, TimeUnit unit, LimitStrategy strategy) {
        KeyResolver resolver = resolvers.get(key);
        if (resolver == null) throw new IllegalStateException("No rate-limit resolver named '" + key + "'; known: " + resolvers.keySet());
        LimitConfig cfg = new LimitConfig(requests, unit.toMillis(window), strategy.create());
        BucketStore store = new BucketStore(maxKeys, cfg.windowMs());
        PreEncodedHeader limit = new PreEncodedHeader("X-RateLimit-Limit", Integer.toString(requests));
        return next -> (req, res) -> {
            long now = System.currentTimeMillis();
            long verdict = cfg.strategy().check(store.get(resolver.resolve(req), now), cfg);
            res.header(limit).header("X-RateLimit-Remaining", remaining(verdict)).header("X-RateLimit-Reset", reset(verdict));
            if (!allowed(verdict)) throw HttpException.tooManyRequests(Math.max(1L, reset(verdict) - now / 1000L));
            return next.handle(req, res);
        };
    }

    @Override
    public void install(App app) {
        app.add(RouteHook.class, new RouteHook() {
            @Override public List<MiddlewareNode> middleware(Class<?> handler) {
                Limit rule = handler.getAnnotation(Limit.class);
                return rule == null ? List.of() : List.of(MiddlewareNode.of(LIMIT,
                        limit(rule.key(), rule.requests(), rule.window(), rule.windowUnit(), rule.strategy())));
            }
        }).install(Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> a -> a.add(OpenApiContributor.class, Docs.class)));
    }

    /** The rate-limit headers and the 429 of every {@link Limit}ed operation. */
    static final class Docs implements OpenApiContributor {

        private static final Map<String, Object> INTEGER = Map.of("type", "integer");

        @Override
        public OpenApiOperationContribution operationFor(Class<?> handler) {
            if (!handler.isAnnotationPresent(Limit.class)) return OpenApiOperationContribution.empty();
            return OpenApiOperationContribution.builder()
                    .allResponses(headers(OpenApiResponseContribution.builder()).build())
                    .response(429, headers(OpenApiResponseContribution.builder()).description("Too Many Requests")
                            .header("Retry-After", header("Seconds to wait before retrying")).build())
                    .build();
        }

        private static OpenApiResponseContribution.Builder headers(OpenApiResponseContribution.Builder response) {
            return response.header("X-RateLimit-Limit", header("Requests allowed per window"))
                    .header("X-RateLimit-Remaining", header("Requests left in the current window"))
                    .header("X-RateLimit-Reset", header("Epoch second the quota resets at"));
        }

        private static Map<String, Object> header(String description) {
            return Map.of("description", description, "schema", INTEGER);
        }
    }
}
