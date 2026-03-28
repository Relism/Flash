package dev.relism.extension;

import java.util.*;
import java.util.stream.Stream;

/**
 * Shared registry passed to every extension during {@link FlashExtension#install}.
 * Extensions use it in two ways:
 * <ol>
 *   <li><b>Service sharing</b> — provide/require typed objects (e.g. {@code ObjectMapper},
 *       {@code OpenApiBuilder}) so extensions can build on each other.</li>
 *   <li><b>Annotation processing</b> — register {@link AnnotationProcessor}s that
 *       are invoked for every handler, injecting middleware derived from
 *       annotations ({@code @RolesAllowed}, {@code @Authenticated}, etc.).</li>
 * </ol>
 *
 * <p>A child context (created via {@link #child()}) inherits all services and processors
 * from its parent. Services provided and processors added on the child are scoped to it
 * and not visible in the parent or sibling scopes.
 */
public class ExtensionContext {

    private final ExtensionContext parent;
    private final Map<Class<?>, Object>     registry       = new LinkedHashMap<>();
    private final List<AnnotationProcessor> processors     = new ArrayList<>();
    private final List<RouteListener>       routeListeners = new ArrayList<>();

    public ExtensionContext() {
        this.parent = null;
    }

    private ExtensionContext(ExtensionContext parent) {
        this.parent = parent;
    }

    /**
     * Creates a child context that inherits this context's services and processors.
     * Services provided and processors added on the child do not affect the parent.
     */
    public ExtensionContext child() {
        return new ExtensionContext(this);
    }

    // ── Service registry ─────────────────────────────────────────────────────

    /** Stores {@code instance} under {@code type} for retrieval by other extensions. */
    public <T> void provide(Class<T> type, T instance) {
        registry.put(type, instance);
    }

    /**
     * Retrieves the service registered under {@code type}.
     * Checks own scope first, then the parent chain.
     * Throws {@link IllegalStateException} if not found — install order matters.
     */
    @SuppressWarnings("unchecked")
    public <T> T require(Class<T> type) {
        T val = (T) registry.get(type);
        if (val == null && parent != null) val = parent.find(type).orElse(null);
        if (val == null)
            throw new IllegalStateException(
                    "Extension dependency not found: " + type.getSimpleName() +
                    " — install the required extension first");
        return val;
    }

    /** Returns the service under {@code type}, or empty if not installed in this scope or any parent. */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> type) {
        T val = (T) registry.get(type);
        if (val != null) return Optional.of(val);
        return parent != null ? parent.find(type) : Optional.empty();
    }

    // ── Annotation processors ────────────────────────────────────────────────

    /**
     * Registers an {@link AnnotationProcessor}. Called by extensions during
     * {@link FlashExtension#install}. Processors are invoked in registration order
     * (parent processors first, then own).
     */
    public void addAnnotationProcessor(AnnotationProcessor processor) {
        processors.add(processor);
    }

    /**
     * Returns all processors visible from this context: parent processors first,
     * then processors added directly to this context.
     */
    List<AnnotationProcessor> processors() {
        if (parent == null) return Collections.unmodifiableList(processors);
        List<AnnotationProcessor> parentProcessors = parent.processors();
        if (processors.isEmpty()) return parentProcessors;
        return Stream.concat(parentProcessors.stream(), processors.stream()).toList();
    }

    // ── Route listeners ──────────────────────────────────────────────────────

    /**
     * Registers a {@link RouteListener} that will be notified once for every route
     * registered on this context's {@link dev.relism.extension.FlashApp} or any
     * {@link dev.relism.extension.FlashScope} that inherits from it.
     *
     * <p>Call this inside {@link FlashExtension#install} to observe all routes.
     * If no listener is registered the emission path is a no-op.
     */
    public void addRouteListener(RouteListener listener) {
        routeListeners.add(listener);
    }

    /**
     * Returns all route listeners visible from this context: parent listeners first,
     * then listeners added directly to this context.
     */
    List<RouteListener> routeListeners() {
        if (parent == null) return Collections.unmodifiableList(routeListeners);
        List<RouteListener> parentListeners = parent.routeListeners();
        if (routeListeners.isEmpty()) return parentListeners;
        return Stream.concat(parentListeners.stream(), routeListeners.stream()).toList();
    }
}
