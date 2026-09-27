package dev.relism.flash;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The services of one {@link App}. Declared with {@code app.add(...)}, built when the server starts,
 * closed when it stops.
 *
 * <p>A class is built by its constructor, and each parameter is looked up here: {@code T} must be
 * added exactly once, {@code Optional<T>} at most once, {@code List<T>} any number of times, in the
 * order it was added. Nobody writes the graph by hand, and the order things are added in never
 * matters. A cycle fails the boot with its path.
 *
 * <p>Every {@link AutoCloseable} service is closed on stop, the ones built here first and in
 * reverse order of creation, then the ones added already built.
 */
@Slf4j
public final class Services {

    private final Map<Class<?>, List<Binding>> bindings = new LinkedHashMap<>();
    private final ArrayDeque<Class<?>> building = new ArrayDeque<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private boolean started;

    Services() {}

    /** The only {@code type} added; fails when it was added none or several times. */
    public <T> T get(Class<T> type) {
        List<Binding> found = bindingsOf(type);
        if (found.size() != 1) throw ambiguous(type, found);
        return type.cast(resolve(found.get(0)));
    }

    /** The {@code type} added, if it was; fails when it was added several times. */
    public <T> Optional<T> find(Class<T> type) {
        List<Binding> found = bindingsOf(type);
        if (found.size() > 1) throw ambiguous(type, found);
        return found.isEmpty() ? Optional.empty() : Optional.of(type.cast(resolve(found.get(0))));
    }

    /** Every {@code type} added, in the order it was added. */
    public <T> List<T> all(Class<T> type) {
        List<Binding> found = bindingsOf(type);
        List<T> out = new ArrayList<>(found.size());
        for (Binding binding : found) out.add(type.cast(resolve(binding)));
        return out;
    }

    /**
     * Builds {@code type}, which is not a service itself, the way services are built: a handler, a
     * tool, anything a module instantiates for the app. A {@link Bound} is bound before it is
     * returned. For boot only.
     */
    public <T> T create(Class<T> type) {
        T instance = type.cast(construct(type));
        if (instance instanceof Bound bound) bound.bind(this);
        return instance;
    }

    // -- Declaration, through App ---------------------------------------------

    void add(Class<?> type, Class<?> implementation) {
        if (!type.isAssignableFrom(implementation))
            throw new IllegalArgumentException(implementation.getName() + " is not a " + type.getName());
        List<Binding> list = declare(type);
        for (Binding existing : list) if (existing.implementation == implementation) return;
        list.add(new Binding(implementation, null));
    }

    void add(Class<?> type, Object instance) {
        declare(type).add(new Binding(instance.getClass(), type.cast(instance)));
    }

    void replace(Class<?> type, Object instance) {
        requireDeclaring();
        List<Binding> list = new ArrayList<>(1);
        list.add(new Binding(instance.getClass(), type.cast(instance)));
        bindings.put(type, list);
    }

    // -- Lifecycle, through Server ---------------------------------------------

    /**
     * Builds every service. What was added already built is bound first, if it is {@link Bound},
     * and closed last.
     */
    void start() {
        started = true;
        List<Object> given = new ArrayList<>();
        for (List<Binding> list : bindings.values())
            for (Binding binding : list)
                if (binding.instance != null && !given.contains(binding.instance)) given.add(binding.instance);
        for (Object instance : given) if (instance instanceof AutoCloseable closeable) closeables.add(closeable);
        for (Object instance : given) if (instance instanceof Bound bound) bound.bind(this);
        for (List<Binding> list : bindings.values()) for (Binding binding : list) resolve(binding);
    }

    void close() {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try {
                closeables.get(i).close();
            } catch (Exception failure) {
                log.error("Closing {} failed", closeables.get(i).getClass().getName(), failure);
            }
        }
        closeables.clear();
    }

    // -- Internals --------------------------------------------------------------

    private List<Binding> declare(Class<?> type) {
        requireDeclaring();
        return bindings.computeIfAbsent(type, ignored -> new ArrayList<>());
    }

    private void requireDeclaring() {
        if (started) throw new IllegalStateException("Services are declared before the server starts");
    }

    private List<Binding> bindingsOf(Class<?> type) {
        if (!started) throw new IllegalStateException(
                "Services are built when the server starts: take " + type.getSimpleName() + " as a constructor parameter");
        return bindings.getOrDefault(type, List.of());
    }

    private Object resolve(Binding binding) {
        if (binding.instance == null) {
            Object built = construct(binding.implementation);
            if (built instanceof Bound bound) bound.bind(this);
            binding.instance = built;
            if (built instanceof AutoCloseable closeable) closeables.add(closeable);
        }
        return binding.instance;
    }

    private Object construct(Class<?> type) {
        if (building.contains(type)) {
            StringBuilder path = new StringBuilder("Circular dependency: ");
            for (var it = building.descendingIterator(); it.hasNext(); ) path.append(it.next().getSimpleName()).append(" -> ");
            throw new IllegalStateException(path.append(type.getSimpleName()).toString());
        }
        Constructor<?> constructor = constructorOf(type);
        Type[] parameters = constructor.getGenericParameterTypes();
        building.push(type);
        try {
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) arguments[i] = argument(parameters[i]);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException failed) {
            throw new IllegalStateException("Building " + type.getName() + " failed: " + failed.getCause(), failed.getCause());
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException("Cannot build " + type.getName(), unreachable);
        } finally {
            building.pop();
        }
    }

    /** The only constructor, or else the only public one. */
    private static Constructor<?> constructorOf(Class<?> type) {
        Constructor<?>[] declared = type.getDeclaredConstructors();
        if (declared.length == 1) return declared[0];
        Constructor<?>[] visible = type.getConstructors();
        if (visible.length == 1) return visible[0];
        throw new IllegalStateException(type.getName() + " has " + visible.length
                + " public constructors: the app builds a class through exactly one");
    }

    private Object argument(Type parameter) {
        if (parameter instanceof Class<?> type) return type == Services.class ? this : get(type);
        if (parameter instanceof ParameterizedType generic && generic.getRawType() instanceof Class<?> raw) {
            Type[] arguments = generic.getActualTypeArguments();
            if (raw == Optional.class) return find(rawOf(arguments[0]));
            if (raw == List.class) return all(rawOf(arguments[0]));
            return get(raw);
        }
        throw new IllegalStateException("Cannot inject " + parameter.getTypeName() + " into " + building.peek().getName());
    }

    private static Class<?> rawOf(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) return c;
        throw new IllegalStateException("Cannot inject " + type.getTypeName());
    }

    private IllegalStateException ambiguous(Class<?> type, List<Binding> found) {
        String owner = building.isEmpty() ? "" : " (needed by " + building.peek().getName() + ")";
        if (found.isEmpty()) return new IllegalStateException("No " + type.getName() + " was added to the app" + owner);
        StringBuilder names = new StringBuilder();
        for (Binding binding : found) names.append(names.isEmpty() ? "" : ", ").append(binding.implementation.getName());
        return new IllegalStateException(type.getName() + " was added " + found.size() + " times (" + names
                + ") but exactly one is needed" + owner);
    }

    private static final class Binding {
        final Class<?> implementation;
        Object instance;

        Binding(Class<?> implementation, Object instance) {
            this.implementation = implementation;
            this.instance = instance;
        }
    }
}
