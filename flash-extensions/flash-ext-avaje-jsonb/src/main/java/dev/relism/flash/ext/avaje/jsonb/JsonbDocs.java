package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import io.avaje.json.JsonAdapter;
import io.avaje.json.view.ViewBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What Jsonb writes a type as, told to the published document: each field under its wire name
 * ({@code @Json.Property}), and none that is not written ({@code @Json.Ignore}).
 *
 * <p>Jsonb's annotations exist only at compile time, so they are not read here: the generated
 * adapter is. It describes its own properties to a {@link ViewBuilder}, naming the Java member
 * each one reads, and that is the whole mapping.
 */
public final class JsonbDocs {

    private JsonbDocs() {}

    /** The contributor, when flash-ext-openapi is on the classpath; nothing otherwise. */
    public static Module module() {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, Fields.class));
    }

    /** Loaded only through {@link #module()}, so this format works without flash-ext-openapi. */
    static final class Fields implements OpenApiContributor {

        private final Json json;
        private final Map<Class<?>, Map<String, String>> written = new HashMap<>();

        Fields(Json json) {
            this.json = json;
        }

        @Override
        public String name(Field field) {
            Map<String, String> names = namesOf(field.getDeclaringClass());
            return names == null ? null : names.get(field.getName());
        }

        @Override
        public boolean ignored(Field field) {
            Map<String, String> names = namesOf(field.getDeclaringClass());
            return names != null && !names.containsKey(field.getName());
        }

        /** Java member → wire name for every property the adapter writes, or null without an adapter. */
        private Map<String, String> namesOf(Class<?> type) {
            return written.computeIfAbsent(type, ignored -> {
                if (!json.jsonb().hasAdapter(type)) return null;
                JsonAdapter<?> adapter = json.jsonb().adapter(type);
                if (!adapter.isViewBuilderAware()) return null;
                Recorder recorder = new Recorder();
                adapter.viewBuild().build(recorder);
                return recorder.names;
            });
        }
    }

    /** Hands the adapter a distinct handle per member it asks for, and reads back which name it wrote each under. */
    private static final class Recorder implements ViewBuilder {

        private final Map<MethodHandle, String> members = new IdentityHashMap<>();
        private final Map<String, String> names = new HashMap<>();

        @Override public MethodHandle field(Class<?> cls, String name) { return member(name); }

        @Override public MethodHandle method(Class<?> cls, String methodName, Class<?> returnType) { return member(methodName); }

        @Override public void add(String name, JsonAdapter<?> adapter, MethodHandle handle) { written(name, handle); }

        @Override public void addArray(String name, JsonAdapter<?> adapter, MethodHandle handle) { written(name, handle); }

        @Override public void beginObject(String name, MethodHandle handle) {}

        @Override public void endObject() {}

        private MethodHandle member(String name) {
            MethodHandle handle = MethodHandles.constant(String.class, name);
            members.put(handle, name);
            return handle;
        }

        private void written(String name, MethodHandle handle) {
            String member = handle == null ? null : members.get(handle);
            if (member != null) names.put(member.startsWith("get") && member.length() > 3 ? decapitalize(member.substring(3)) : member, name);
        }

        private static String decapitalize(String name) {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
    }
}
