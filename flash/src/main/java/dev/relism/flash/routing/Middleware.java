package dev.relism.flash.routing;

import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.SimpleHandler;

/**
 * Stateless interceptor applied around a {@link RequestHandler}.
 *
 * <p>Middleware is composed <em>once at registration time</em> and stored as a pre-built handler
 * chain inside the router's state machine. There is zero runtime overhead: no lists are visited,
 * no lookups performed, and no objects allocated on the request hot-path.
 *
 * <p>Middleware is intentionally stateless — it can read {@link Request},
 * write to {@link Response}, and decide whether to call {@code next} or
 * short-circuit. Shared state between layers belongs in HDI handler chains, not here.
 *
 * <pre>{@code
 * // Inline definition — pure lambda, no wrapper types needed
 * Middleware auth = next -> (req, res) -> {
 *     if (req.header("Authorization") == null) { res.status(401); return null; }
 *     return next.handle(req, res);
 * };
 *
 * // Named reusable chain — composed into a single object at call time
 * Middleware secured = Middleware.of(cors, auth, rateLimit);
 *
 * // Router-level: applied to every handler registered on this router
 * AbstractRouter api = new MyRouter(cors, auth);
 *
 * // Handler-level: applied only to this route
 * app.get("/admin", (req, res) -> "secret", auth);
 * app.get("/log",   handler, logging, auth);
 * }</pre>
 */
@FunctionalInterface
public interface Middleware {

    /**
     * Wraps {@code next} with this middleware's logic. Called <em>once at boot time</em>
     * during handler registration. Returns a {@link SimpleHandler.FunctionalHandler} lambda
     * — no wrapper types required at the call site.
     *
     * <p>The returned functional handler is boxed into a {@link SimpleHandler} internally by
     * {@link AbstractRouter}; callers never need to do this manually.
     */
    SimpleHandler.FunctionalHandler wrap(RequestHandler next);

    /**
     * Composes an ordered chain of middlewares into a single {@link Middleware}.
     * The first element is the outermost wrapper and therefore executes first.
     *
     * <p>The composition loop runs once when {@link #wrap} is called (i.e. at registration
     * time), not at construction of the chain. Zero allocations on the hot-path.
     *
     * <pre>{@code
     * Middleware secured = Middleware.of(cors, auth, rateLimit);
     * // execution order: cors → auth → rateLimit → handler
     * }</pre>
     */
    static Middleware of(Middleware... chain) {
        if (chain.length == 0) return next -> next::handle;
        if (chain.length == 1) return chain[0];
        return next -> {
            RequestHandler h = next;
            for (int i = chain.length - 1; i >= 0; i--)
                h = new SimpleHandler(chain[i].wrap(h));
            return h::handle;
        };
    }

}
