package dev.relism.ext.view;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ViewModel {
    private final LinkedHashMap<String, Object> values;

    private ViewModel(LinkedHashMap<String, Object> values) {
        this.values = values;
    }

    public static ViewModel empty() {
        return new ViewModel(new LinkedHashMap<>());
    }

    public static ViewModel of(String key, Object value) {
        return empty().with(key, value);
    }

    public ViewModel with(String key, Object value) {
        values.put(Objects.requireNonNull(key, "key"), unwrapValue(value));
        return this;
    }

    public ViewModel withAll(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return this;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            with(e.getKey(), e.getValue());
        }
        return this;
    }

    public ViewModel merge(ViewModel other) {
        ViewModel merged = new ViewModel(new LinkedHashMap<>(this.values));
        if (other != null && !other.values.isEmpty()) merged.values.putAll(other.values);
        return merged;
    }

    public Map<String, Object> toMap() {
        return Collections.unmodifiableMap(values);
    }

    static ViewModel copyOf(ViewModel source) {
        if (source == null || source.values.isEmpty()) return empty();
        return new ViewModel(new LinkedHashMap<>(source.values));
    }

    static Object unwrapValue(Object value) {
        if (value instanceof ViewModel vm) {
            return vm.toMap();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), unwrapValue(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(unwrapValue(item));
            return Collections.unmodifiableList(out);
        }
        return value;
    }
}
