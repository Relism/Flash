package dev.relism.flash.extension;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Deterministic boot-time service graph, frozen before handlers are initialised. */
public final class FlashContext {
    private enum State { DECLARING, RESOLVING, READY }

    private final FlashContext parent;
    private final Map<Class<?>, Binding<?>> bindings = new LinkedHashMap<>();
    private final List<AnnotationProcessor> processors = new ArrayList<>();
    private final List<RouteListener> routeListeners = new ArrayList<>();
    private final List<Runnable> readyCallbacks = new ArrayList<>();
    private final List<FlashContext> children = new ArrayList<>();
    private final Deque<Class<?>> resolutionPath = new ArrayDeque<>();
    private State state = State.DECLARING;
    private List<AnnotationProcessor> cachedProcessors;
    private List<RouteListener> cachedListeners;

    public FlashContext() { parent = null; }
    private FlashContext(FlashContext parent) { this.parent = parent; }

    public FlashContext child() {
        requireDeclaring();
        FlashContext child = new FlashContext(this);
        children.add(child);
        return child;
    }

    /** Binds an already-created singleton. Duplicate bindings are always an error. */
    public <T> void provide(Class<T> type, T instance) {
        declare(type, new Binding<>(type, List.of(), ignored -> Objects.requireNonNull(instance, "instance")));
    }

    /** Declares a no-dependency boot factory. */
    public <T> void supply(Class<T> type, Supplier<T> factory) {
        declare(type, new Binding<>(type, List.of(), ignored -> factory.get()));
    }

    /** Declares a boot factory and its complete dependency set. */
    public <T> void supply(Class<T> type, ServiceFactory<T> factory, Class<?>... dependencies) {
        Objects.requireNonNull(factory, "factory");
        declare(type, new Binding<>(type, List.of(dependencies), factory));
    }

    /** One-dependency factory with no application-side context lookup. */
    public <A, T> void supply(Class<T> type, Class<A> dependency, Function<A, T> factory) {
        supply(type, ignored -> factory.apply(require(dependency)), dependency);
    }

    /** Registers work materialised after all services are resolved. */
    public void onReady(Runnable callback) { requireDeclaring(); readyCallbacks.add(Objects.requireNonNull(callback)); }

    @SuppressWarnings("unchecked")
    public <T> T require(Class<T> type) {
        if (state == State.DECLARING)
            throw new IllegalStateException("Service graph is still being declared; use FlashContext.onReady(...)");
        Binding<?> binding = bindings.get(type);
        if (binding != null) {
            verifyDeclaredDependency(type);
            return (T) resolve((Binding<Object>) binding);
        }
        if (parent != null) return parent.require(type);
        throw new IllegalStateException("No provider declared for " + type.getName() + dependencyTrace());
    }

    public <T> Optional<T> find(Class<T> type) {
        if (state == State.DECLARING)
            throw new IllegalStateException("Service graph is still being declared; use FlashContext.onReady(...)");
        if (bindings.containsKey(type)) return Optional.of(require(type));
        return parent == null ? Optional.empty() : parent.find(type);
    }
    public <T> Optional<T> optional(Class<T> type) { return find(type); }

    public void addAnnotationProcessor(AnnotationProcessor processor) {
        requireDeclaring(); processors.add(Objects.requireNonNull(processor)); cachedProcessors = null;
    }
    public void addRouteListener(RouteListener listener) {
        requireDeclaring(); routeListeners.add(Objects.requireNonNull(listener)); cachedListeners = null;
    }

    List<AnnotationProcessor> processors() {
        if (cachedProcessors != null) return cachedProcessors;
        List<AnnotationProcessor> all = parent == null ? new ArrayList<>() : new ArrayList<>(parent.processors());
        all.addAll(processors); return cachedProcessors = List.copyOf(all);
    }
    List<RouteListener> routeListeners() {
        if (cachedListeners != null) return cachedListeners;
        List<RouteListener> all = parent == null ? new ArrayList<>() : new ArrayList<>(parent.routeListeners());
        all.addAll(routeListeners); return cachedListeners = List.copyOf(all);
    }

    void resolveAll() {
        if (state != State.DECLARING) throw new IllegalStateException("Service graph has already been closed");
        state = State.RESOLVING;
        for (Binding<?> binding : bindings.values()) resolveUnchecked(binding);
        for (FlashContext child : children) child.resolveAll();
        state = State.READY;
    }
    void runReadyCallbacks() {
        if (state != State.READY) throw new IllegalStateException("Service graph is not ready");
        for (Runnable callback : List.copyOf(readyCallbacks)) callback.run();
        readyCallbacks.clear();
        for (FlashContext child : children) child.runReadyCallbacks();
    }

    /** Completes graph resolution and runs all deferred materialisation callbacks once. */
    public void complete() {
        resolveAll();
        runReadyCallbacks();
    }

    private <T> void declare(Class<T> type, Binding<T> binding) {
        requireDeclaring(); Objects.requireNonNull(type, "type");
        if (bindings.putIfAbsent(type, binding) != null)
            throw new IllegalStateException("Duplicate provider declared for " + type.getName());
    }
    private void requireDeclaring() {
        if (state != State.DECLARING) throw new IllegalStateException("Flash service declarations are closed");
    }
    @SuppressWarnings("unchecked") private void resolveUnchecked(Binding<?> binding) { resolve((Binding<Object>) binding); }
    private <T> T resolve(Binding<T> binding) {
        if (binding.instance != null) return binding.instance;
        if (binding.resolving) throw cycle(binding.type);
        binding.resolving = true; resolutionPath.addLast(binding.type);
        try {
            for (Class<?> dependency : binding.dependencies) require(dependency);
            return binding.instance = Objects.requireNonNull(binding.factory.create(this),
                    () -> "Provider returned null for " + binding.type.getName());
        } finally {
            resolutionPath.removeLast(); binding.resolving = false;
        }
    }
    private IllegalStateException cycle(Class<?> type) {
        StringBuilder out = new StringBuilder("Circular service dependency: ");
        for (Class<?> node : resolutionPath) out.append(node.getSimpleName()).append(" -> ");
        return new IllegalStateException(out.append(type.getSimpleName()).toString());
    }
    private String dependencyTrace() {
        return resolutionPath.isEmpty() ? "" : " (required while creating " + resolutionPath.peekLast().getName() + ')';
    }
    private void verifyDeclaredDependency(Class<?> type) {
        if (resolutionPath.isEmpty()) return;
        Class<?> owner = resolutionPath.peekLast();
        Binding<?> binding = bindings.get(owner);
        if (binding != null && !binding.dependencies.contains(type))
            throw new IllegalStateException(owner.getName() + " requested undeclared dependency " + type.getName());
    }

    @FunctionalInterface public interface ServiceFactory<T> { T create(FlashContext services); }
    private static final class Binding<T> {
        final Class<T> type; final List<Class<?>> dependencies; final ServiceFactory<T> factory;
        T instance; boolean resolving;
        Binding(Class<T> type, List<Class<?>> dependencies, ServiceFactory<T> factory) {
            this.type = type; this.dependencies = dependencies; this.factory = factory;
        }
    }
}
