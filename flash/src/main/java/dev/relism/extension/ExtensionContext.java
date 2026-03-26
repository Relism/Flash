package dev.relism.extension;

import java.util.*;

/**
 * Shared registry passed to every extension during {@link FlashExtension#install}.
 * Extensions use it in two ways:
 * <ol>
 *   <li><b>Service sharing</b> — provide/require typed objects (e.g. {@code ObjectMapper},
 *       {@code OpenApiBuilder}) so extensions can build on each other.</li>
 *   <li><b>Annotation processing</b> — register {@link AnnotationProcessor}s that
 *       {@link FlashApp} calls for every handler, injecting middleware derived from
 *       annotations ({@code @RolesAllowed}, {@code @Authenticated}, etc.).</li>
 * </ol>
 */
public class ExtensionContext {

    private final Map<Class<?>, Object>     registry   = new LinkedHashMap<>();
    private final List<AnnotationProcessor> processors = new ArrayList<>();

    // ── Service registry ─────────────────────────────────────────────────────

    /** Stores {@code instance} under {@code type} for retrieval by other extensions. */
    public <T> void provide(Class<T> type, T instance) {
        registry.put(type, instance);
    }

    /**
     * Retrieves the service registered under {@code type}.
     * Throws {@link IllegalStateException} if not present — install order matters.
     */
    @SuppressWarnings("unchecked")
    public <T> T require(Class<T> type) {
        T val = (T) registry.get(type);
        if (val == null)
            throw new IllegalStateException(
                    "Extension dependency not found: " + type.getSimpleName() +
                    " — install the required extension first");
        return val;
    }

    /** Returns the service under {@code type}, or empty if not installed. */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable((T) registry.get(type));
    }

    // ── Annotation processors ────────────────────────────────────────────────

    /**
     * Registers an {@link AnnotationProcessor}. Called by extensions during
     * {@link FlashExtension#install}. Processors are invoked in registration order.
     */
    public void addAnnotationProcessor(AnnotationProcessor processor) {
        processors.add(processor);
    }

    /** Returns an unmodifiable view of all registered processors. */
    List<AnnotationProcessor> processors() {
        return Collections.unmodifiableList(processors);
    }
}
