package dev.relism.extension;

/**
 * Contract for all Flash extensions. An extension receives a {@link FlashRegistrar}
 * (either a {@link FlashApp} or a {@link FlashScope}) so it can register routes and
 * expose shared services via {@link ExtensionContext}.
 *
 * <p>Extensions work identically whether installed at the top-level app or inside a
 * mounted scope:
 *
 * <pre>{@code
 * public class RateLimitExtension implements FlashExtension {
 *     public void install(FlashRegistrar app, ExtensionContext ctx) {
 *         RateLimiter limiter = new RateLimiter(100);
 *         ctx.provide(RateLimiter.class, limiter);
 *         app.onException((ex, req, res) -> { ... });
 *     }
 * }
 *
 * // Top-level app
 * FlashApp.create(8080)
 *     .install(new JacksonExtension())
 *     .install(new OidcExtension(OidcConfig.fromEnv()));
 *
 * // Scoped
 * app.mount("/api", scope -> scope.install(new RateLimitExtension()));
 * }</pre>
 */
@FunctionalInterface
public interface FlashExtension {
    void install(FlashRegistrar app, ExtensionContext ctx);
}
