package dev.relism.flash.extension;

import java.util.*;
import java.util.function.Supplier;

/**
 * Central service registry and boot-time hook coordinator.
 *
 * <p>Every handler, extension, and scope shares one (or a child of one) {@code FlashContext}.
 * Three capabilities:
 * <ol>
 *   <li><b>Service registry</b> — {@link #provide}/{@link #supply}/{@link #require}/{@link #find}.</li>
 *   <li><b>Annotation processors</b> — middleware injection from handler annotations at boot.</li>
 *   <li><b>Route listeners</b> — boot-time observation of the route graph.</li>
 * </ol>
 *
 * <h3>Eager vs lazy registration</h3>
 * <ul>
 *   <li>{@link #provide(Class, Object)} — instance is already constructed, registered immediately.</li>
 *   <li>{@link #supply(Class, Supplier)} — factory is registered; it runs once, at
 *       {@link #resolveAll()} time (called by {@link FlashApp#start()}) after all
 *       {@link FlashExtension#provide} phases complete. The factory may call
 *       {@link #require} for its own dependencies — the runtime resolves in topological
 *       order automatically and reports circular dependencies with the full cycle path.</li>
 * </ul>
 *
 * <p>A child context (via {@link #child()}) inherits parent services and processors.
 * Services provided on the child are scoped and invisible to the parent.
 */
public class FlashContext {

    private final FlashContext parent;
    private final Map<Class<?>, Object>      registry  = new LinkedHashMap<>();
    private final Map<Class<?>, Supplier<?>> pending   = new LinkedHashMap<>();
    private final List<AnnotationProcessor>  processors     = new ArrayList<>();
    private final List<RouteListener>        routeListeners = new ArrayList<>();

    // Lazy caches — nulled whenever the corresponding list is mutated.
    private List<AnnotationProcessor> cachedProcessors;
    private List<RouteListener>       cachedListeners;

    // DFS stack — tracks in-progress resolutions to detect circular dependencies.
    private final LinkedHashSet<Class<?>> resolutionStack = new LinkedHashSet<>();

    public FlashContext() { this.parent = null; }
    private FlashContext(FlashContext parent) { this.parent = parent; }

    /** Creates a child context that inherits this context's services and processors. */
    public FlashContext child() { return new FlashContext(this); }

    // ── Service registry ─────────────────────────────────────────────────────

    /** Registers an already-constructed {@code instance} under {@code type}. */
    public <T> void provide(Class<T> type, T instance) {
        registry.put(type, instance);
    }

    /**
     * Registers a lazy factory for {@code type}. The factory runs once at
     * {@link #resolveAll()} time (or on the first {@link #require} call for this type)
     * and may call {@link #require} for its own dependencies — topological order
     * is resolved automatically.
     *
     * <pre>{@code
     * ctx.supply(JwtValidator.class, () ->
     *     new JwtValidator(ctx.require(OidcProviderMetadata.class).jwksUri()));
     * }</pre>
     */
    public <T> void supply(Class<T> type, Supplier<T> factory) {
        pending.put(type, factory);
    }

    /**
     * Returns the service for {@code type}. Checks own scope first, then parent chain.
     * Lazy-registered types are resolved on first access. Circular dependencies throw
     * {@link IllegalStateException} with the full cycle path.
     *
     * @throws IllegalStateException if the service is not found anywhere in the context chain
     */
    @SuppressWarnings("unchecked")
    public <T> T require(Class<T> type) {
        Object val = registry.get(type);
        if (val != null) return (T) val;
        if (pending.containsKey(type)) return resolve(type);
        if (parent != null) return parent.require(type);
        throw new IllegalStateException(
                "Service not found: " + type.getSimpleName() +
                " — register it via FlashContext.provide()/supply() or install the required extension");
    }

    /** Returns the service for {@code type}, or empty if not found in this scope or any parent. */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> type) {
        Object val = registry.get(type);
        if (val != null) return Optional.of((T) val);
        if (pending.containsKey(type)) return Optional.of(resolve(type));
        return parent != null ? parent.find(type) : Optional.empty();
    }

    /** Alias for {@link #find} — prefer when semantics are "this may or may not exist". */
    public <T> Optional<T> optional(Class<T> type) { return find(type); }

    /**
     * Eagerly resolves all pending lazy suppliers in topological order.
     * Called once by {@link FlashApp#start()} after all {@link FlashExtension#provide}
     * phases complete. Any circular dependency is reported with the full cycle path.
     */
    void resolveAll() {
        new ArrayList<>(pending.keySet()).forEach(this::resolve);
    }

    @SuppressWarnings("unchecked")
    private <T> T resolve(Class<?> type) {
        Object already = registry.get(type);
        if (already != null) return (T) already;  // resolved during an earlier DFS branch

        if (!resolutionStack.add(type)) {
            // type is already on the current DFS path → circular dependency
            List<Class<?>> cycle = new ArrayList<>(resolutionStack);
            cycle.add(type);
            StringBuilder msg = new StringBuilder("Circular dependency: ");
            for (int i = 0; i < cycle.size(); i++) {
                if (i > 0) msg.append(" → ");
                msg.append(cycle.get(i).getSimpleName());
            }
            throw new IllegalStateException(msg.toString());
        }

        Supplier<?> factory = pending.get(type);
        Object instance = factory.get();   // recursive require() calls happen here
        registry.put(type, instance);
        pending.remove(type);
        resolutionStack.remove(type);
        return (T) instance;
    }

    // ── Annotation processors ────────────────────────────────────────────────

    /** Registers an {@link AnnotationProcessor}. Processors run once per class-based handler at boot. */
    public void addAnnotationProcessor(AnnotationProcessor processor) {
        processors.add(processor);
        cachedProcessors = null;
    }

    /** All processors visible from this context: parent-first, then own. Cached after first call. */
    List<AnnotationProcessor> processors() {
        if (cachedProcessors != null) return cachedProcessors;
        if (parent == null) return cachedProcessors = List.copyOf(processors);
        List<AnnotationProcessor> p = parent.processors();
        if (processors.isEmpty()) return cachedProcessors = p;
        List<AnnotationProcessor> merged = new ArrayList<>(p.size() + processors.size());
        merged.addAll(p);
        merged.addAll(processors);
        return cachedProcessors = List.copyOf(merged);
    }

    // ── Route listeners ──────────────────────────────────────────────────────

    /** Registers a boot-time {@link RouteListener}. Zero overhead on the request hot-path. */
    public void addRouteListener(RouteListener listener) {
        routeListeners.add(listener);
        cachedListeners = null;
    }

    /** All route listeners visible from this context: parent-first, then own. Cached after first call. */
    List<RouteListener> routeListeners() {
        if (cachedListeners != null) return cachedListeners;
        if (parent == null) return cachedListeners = List.copyOf(routeListeners);
        List<RouteListener> p = parent.routeListeners();
        if (routeListeners.isEmpty()) return cachedListeners = p;
        List<RouteListener> merged = new ArrayList<>(p.size() + routeListeners.size());
        merged.addAll(p);
        merged.addAll(routeListeners);
        return cachedListeners = List.copyOf(merged);
    }
}