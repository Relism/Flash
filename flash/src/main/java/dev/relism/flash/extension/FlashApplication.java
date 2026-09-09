package dev.relism.flash.extension;

/**
 * One application's complete contribution to a {@link FlashApp} — its routes, extensions and
 * services — expressed independently of which port or configuration it runs on.
 *
 * <p>Optional. The fluent form keeps working exactly as before; this interface exists so the
 * same application can be created more than once, on more than one port:
 *
 * <pre>{@code
 * public final class BlogApp implements FlashApplication {
 *     @Override public void configure(FlashApp app) {
 *         app.install(new JacksonExtension());
 *         app.mount("/api", scope -> scope.scan("dev.blog.api"));
 *         app.ws("/live", new FeedSocket());
 *     }
 * }
 *
 * // production
 * FlashApp.create(8080).apply(new BlogApp()).startAndBlock();
 * }</pre>
 *
 * <p>It takes {@link FlashApp} rather than {@link FlashRegistrar} deliberately: {@code ws} and
 * {@code mount} live on {@code FlashApp}, and an application that could not register a
 * WebSocket route or a mounted namespace would be a half-application.
 *
 * <p>Being a {@code @FunctionalInterface}, a lambda and a named class are the same thing here —
 * {@code app -> app.get("/ping", ...)} is as valid as {@code new BlogApp()}.
 *
 * @see FlashApp#apply(FlashApplication)
 */
@FunctionalInterface
public interface FlashApplication {

    /**
     * Registers this application onto {@code app}. Called immediately by
     * {@link FlashApp#apply}, so the service graph is still open for declarations
     * ({@code app.ctx().supply(...)}) and the listener is already bound ({@code app.port()}).
     */
    void configure(FlashApp app);
}
