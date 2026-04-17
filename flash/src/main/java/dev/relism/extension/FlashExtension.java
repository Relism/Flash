package dev.relism.extension;

/**
 * Two-phase contract for all Flash extensions.
 *
 * <p>Extension lifecycle inside {@link FlashApp#start()}:
 * <ol>
 *   <li>Extensions are sorted by {@link #priority()} — lower value runs first.</li>
 *   <li><b>Provide phase</b> — {@link #provide(FlashContext)} is called for <em>all</em>
 *       installed extensions. Use this phase to register services, annotation processors,
 *       and route listeners. Never call {@link FlashContext#require} here.</li>
 *   <li>Context resolution — {@link FlashContext#resolveAll()} performs topological
 *       resolution of lazy suppliers. Circular or missing dependencies fail here with
 *       a clear message before any request is served.</li>
 *   <li><b>Routes phase</b> — {@link #routes(FlashRegistrar, FlashContext)} is called for
 *       all extensions. All services are resolved; {@link FlashContext#require} is safe.</li>
 * </ol>
 *
 * <h3>Priority and middleware ordering</h3>
 * {@link #priority()} controls the order annotation processors are registered, which
 * determines the annotation-layer middleware chain position:
 * <pre>
 * Request ──► EARLY processors' mw ──► DEFAULT processors' mw ──► LATE processors' mw ──► handler
 * </pre>
 * Use {@link ExtensionPhase} constants for semantic ordering:
 * <pre>{@code
 * @Override public int priority() { return ExtensionPhase.EARLY.value; }
 * }</pre>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * public class MetricsExtension implements FlashExtension {
 *
 *     @Override public int priority() { return ExtensionPhase.LATE.value; }
 *
 *     @Override
 *     public void provide(FlashContext ctx) {
 *         ctx.provide(MetricsRegistry.class, new PromMetricsRegistry());
 *     }
 *
 *     @Override
 *     public void routes(FlashRegistrar<?> app, FlashContext ctx) {
 *         app.get("/metrics", (req, res) -> ctx.require(MetricsRegistry.class).scrape());
 *     }
 * }
 * }</pre>
 */
public interface FlashExtension {

    /**
     * Phase 1 — register services and processors.
     * Safe: {@link FlashContext#provide}, {@link FlashContext#supply},
     * {@link FlashContext#addAnnotationProcessor}, {@link FlashContext#addRouteListener}.
     * Unsafe: {@link FlashContext#require} (services not yet resolved).
     */
    default void provide(FlashContext ctx) {}

    /**
     * Phase 2 — register routes. All services are fully resolved.
     * {@link FlashContext#require} is safe here.
     */
    default void routes(FlashRegistrar<?> app, FlashContext ctx) {}

    /**
     * Execution priority. Lower = earlier in the annotation middleware chain.
     * Tie-breaking: same value → install order (sort is stable).
     * Default: {@link ExtensionPhase#DEFAULT} (500).
     */
    default int priority() { return ExtensionPhase.DEFAULT.value; }
}
