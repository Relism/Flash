package dev.relism.extension;

import java.util.*;
import java.util.stream.Stream;

/**
 * Central service registry and boot-time hook coordinator.
 *
 * <p>Every handler, extension, and scope shares one (or a child of one) {@code FlashContext}.
 * It provides three capabilities:
 * <ol>
 *   <li><b>Service registry</b> — typed {@link #provide}/{@link #require}/{@link #find}.</li>
 *   <li><b>Annotation processors</b> — middleware injection from handler annotations.</li>
 *   <li><b>Route listeners</b> — boot-time observation of the route graph.</li>
 * </ol>
 *
 * <p>A child context (via {@link #child()}) inherits parent services and processors.
 * Services provided on the child are scoped and invisible to the parent.
 */
public class FlashContext {

    private final FlashContext parent;
    private final Map<Class<?>, Object>     registry       = new LinkedHashMap<>();
    private final List<AnnotationProcessor> processors     = new ArrayList<>();
    private final List<RouteListener>       routeListeners = new ArrayList<>();

    public FlashContext() {
        this.parent = null;
    }

    private FlashContext(FlashContext parent) {
        this.parent = parent;
    }

    /** Creates a child context that inherits this context's services and processors. */
    public FlashContext child() {
        return new FlashContext(this);
    }

    // ── Service registry ─────────────────────────────────────────────────────

    /** Stores {@code instance} under {@code type} for retrieval via {@link #require} or {@link #find}. */
    public <T> void provide(Class<T> type, T instance) {
        registry.put(type, instance);
    }

    /**
     * Retrieves the service registered under {@code type}.
     * Checks own scope first, then the parent chain.
     *
     * @throws IllegalStateException if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T require(Class<T> type) {
        T val = (T) registry.get(type);
        if (val == null && parent != null) val = parent.find(type).orElse(null);
        if (val == null)
            throw new IllegalStateException(
                    "Service not found: " + type.getSimpleName() +
                    " — provide it via FlashContext.provide() or install the required extension");
        return val;
    }

    /** Returns the service under {@code type}, or empty if not provided in this scope or any parent. */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> type) {
        T val = (T) registry.get(type);
        if (val != null) return Optional.of(val);
        return parent != null ? parent.find(type) : Optional.empty();
    }

    /**
     * Returns the service registered under {@code type} as an {@link Optional},
     * or {@link Optional#empty()} if not present in this scope or any parent.
     *
     * <p>Semantically identical to {@link #find} — prefer this name for expressive call sites
     * ({@code ctx.optional(ViewEngine.class).ifPresent(...)}). Respects the parent-first
     * scope hierarchy: own registry is checked first, then the parent chain.
     */
    public <T> Optional<T> optional(Class<T> type) {
        return find(type);
    }

    // ── Annotation processors ────────────────────────────────────────────────

    /** Registers an {@link AnnotationProcessor}. Processors run once per class-based handler at boot. */
    public void addAnnotationProcessor(AnnotationProcessor processor) {
        processors.add(processor);
    }

    /** All processors visible from this context: parent-first, then own. */
    List<AnnotationProcessor> processors() {
        if (parent == null) return Collections.unmodifiableList(processors);
        List<AnnotationProcessor> parentProcessors = parent.processors();
        if (processors.isEmpty()) return parentProcessors;
        return Stream.concat(parentProcessors.stream(), processors.stream()).toList();
    }

    // ── Route listeners ──────────────────────────────────────────────────────

    /** Registers a boot-time {@link RouteListener}. Zero overhead on the request hot-path. */
    public void addRouteListener(RouteListener listener) {
        routeListeners.add(listener);
    }

    /** All route listeners visible from this context: parent-first, then own. */
    List<RouteListener> routeListeners() {
        if (parent == null) return Collections.unmodifiableList(routeListeners);
        List<RouteListener> parentListeners = parent.routeListeners();
        if (routeListeners.isEmpty()) return parentListeners;
        return Stream.concat(parentListeners.stream(), routeListeners.stream()).toList();
    }
}
