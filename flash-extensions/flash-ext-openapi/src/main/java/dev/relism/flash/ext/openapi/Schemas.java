package dev.relism.flash.ext.openapi;


import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every schema the document names, and the types they were built from.
 *
 * <p>A type is described once and referenced by {@code $ref} everywhere it appears, so a document
 * over a hundred routes carries one copy of each model. What a field means comes from the type
 * itself: the format module that writes it says what each field is called and whether it is
 * exposed, a validation engine's rules become the schema's own bounds, both through contributors,
 * and {@link Schema}/{@link SchemaProperty} say the rest.
 */
final class Schemas {

    /** Whoever knows a field's wire name or what a rule means, set when the document is assembled. */
    private List<OpenApiContributor> contributors = List.of();

    private static final Set<Class<?>> SIMPLE = Set.of(
            String.class, CharSequence.class,
            Boolean.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
            boolean.class, byte.class, short.class, int.class, long.class, float.class, double.class,
            UUID.class, LocalDate.class, LocalDateTime.class, OffsetDateTime.class, Instant.class
    );

    private final Map<Class<?>, String> names = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
    private final Set<Class<?>> resolving = new HashSet<>();

    Map<String, Object> referenceFor(Class<?> type) {
        return schemaFor(type);
    }

    Map<String, Object> schemaForType(Type type) {
        return schemaFor(type);
    }

    Map<String, Object> render() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : docs.entrySet()) out.put(e.getKey(), e.getValue());
        return out;
    }

    private Map<String, Object> schemaFor(Type type) {
        if (type instanceof ParameterizedType p) {
            Class<?> raw = rawType(p);
            if (raw != null && Collection.class.isAssignableFrom(raw)) {
                Type item = p.getActualTypeArguments()[0];
                return Map.of("type", "array", "items", schemaFor(item));
            }
            if (raw != null && Map.class.isAssignableFrom(raw)) {
                Type value = p.getActualTypeArguments().length > 1 ? p.getActualTypeArguments()[1] : Object.class;
                return Map.of("type", "object", "additionalProperties", schemaFor(value));
            }
            if (raw != null) return schemaFor(raw);
        }

        Class<?> cls = rawType(type);
        if (cls == null || cls == Object.class) return Map.of("type", "object");

        if (cls.isArray()) return Map.of("type", "array", "items", schemaFor(cls.getComponentType()));
        if (Collection.class.isAssignableFrom(cls)) return Map.of("type", "array", "items", Map.of("type", "object"));
        if (Map.class.isAssignableFrom(cls)) return Map.of("type", "object", "additionalProperties", Map.of("type", "object"));

        Map<String, Object> simple = simpleSchema(cls);
        if (simple != null) return simple;

        return Map.of("$ref", "#/components/schemas/" + registerPojo(cls));
    }

    private String registerPojo(Class<?> cls) {
        String existing = names.get(cls);
        if (existing != null) return existing;

        String base = schemaName(cls);
        String name = base;
        int i = 2;
        while (docs.containsKey(name)) name = base + i++;
        names.put(cls, name);

        if (resolving.contains(cls)) return name;

        resolving.add(cls);
        docs.put(name, buildPojoSchema(cls));
        resolving.remove(cls);
        return name;
    }

    private Map<String, Object> buildPojoSchema(Class<?> cls) {
        Schema typeSchema = cls.getAnnotation(Schema.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        if (typeSchema != null) applySchemaHints(out, typeSchema);

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Field f : cls.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
            if (ignored(f)) continue;

            String name = wireName(f);

            Schema ps = f.getAnnotation(Schema.class);
            SchemaProperty sp = f.getAnnotation(SchemaProperty.class);
            ArraySchema array = f.getAnnotation(ArraySchema.class);
            if ((ps != null && ps.hidden()) || (sp != null && sp.hidden())) continue;

            if (sp != null && !sp.name().isEmpty()) name = sp.name();

            Map<String, Object> property = new LinkedHashMap<>(schemaFor(f.getGenericType()));
            if (ps != null) applySchemaHints(property, ps);
            if (sp != null) applySchemaHints(property, sp);
            if (array != null) applyArrayHints(property, array);
            // Whoever writes the field or checks it says what that means here — the format its
            // access, the validation engine its rules — rather than callers restating it as @Schema.
            boolean constrainedRequired = contributed(f, property);

            properties.put(name, property);
            if (constrainedRequired
                    || (ps != null && ps.required()) || (sp != null && sp.required()))
                required.add(name);
        }

        if (!properties.isEmpty()) out.put("properties", properties);
        if (!required.isEmpty()) out.put("required", required);
        return out;
    }

    void contributors(List<OpenApiContributor> contributors) {
        this.contributors = contributors;
    }

    private boolean ignored(Field field) {
        for (OpenApiContributor contributor : contributors) if (contributor.ignored(field)) return true;
        return false;
    }

    /** The first name a contributor gives the field, or its own. */
    private String wireName(Field field) {
        for (OpenApiContributor contributor : contributors) {
            String name = contributor.name(field);
            if (name != null) return name;
        }
        return field.getName();
    }

    /** True when a contributor says the property is required. */
    private boolean contributed(Field field, Map<String, Object> property) {
        boolean required = false;
        for (OpenApiContributor contributor : contributors) {
            required |= contributor.property(field, property);
        }
        return required;
    }

    private static void applySchemaHints(Map<String, Object> target, Schema schema) {
        if (!schema.title().isEmpty()) target.put("title", schema.title());
        if (!schema.description().isEmpty()) target.put("description", schema.description());
        if (!schema.format().isEmpty()) target.put("format", schema.format());
        if (!schema.example().isEmpty()) target.put("example", schema.example());
        if (schema.enumeration().length > 0) target.put("enum", Arrays.asList(schema.enumeration()));
        if (schema.nullable()) target.put("nullable", true);
        if (schema.deprecated()) target.put("deprecated", true);
    }

    private static void applySchemaHints(Map<String, Object> target, SchemaProperty schema) {
        if (!schema.title().isEmpty()) target.put("title", schema.title());
        if (!schema.description().isEmpty()) target.put("description", schema.description());
        if (!schema.format().isEmpty()) target.put("format", schema.format());
        if (!schema.example().isEmpty()) target.put("example", schema.example());
        if (schema.enumeration().length > 0) target.put("enum", Arrays.asList(schema.enumeration()));
        if (schema.nullable()) target.put("nullable", true);
        if (schema.deprecated()) target.put("deprecated", true);
    }

    private Map<String, Object> withArrayType(Map<String, Object> property, ArraySchema array) {
        if ("array".equals(property.get("type"))) return property;
        Type itemType = array.itemClass() != Void.class ? array.itemClass() : Object.class;
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("type", "array");
        wrapped.put("items", schemaFor(itemType));
        return wrapped;
    }

    private void applyArrayHints(Map<String, Object> property, ArraySchema array) {
        Map<String, Object> target = withArrayType(property, array);
        if (target != property) {
            property.clear();
            property.putAll(target);
        }
        if (array.uniqueItems()) property.put("uniqueItems", true);
        if (array.minItems() >= 0) property.put("minItems", array.minItems());
        if (array.maxItems() >= 0) property.put("maxItems", array.maxItems());
    }

    private static String schemaName(Class<?> cls) {
        Schema schema = cls.getAnnotation(Schema.class);
        if (schema != null && !schema.name().isEmpty()) return schema.name();
        return cls.getSimpleName();
    }

    private static Map<String, Object> simpleSchema(Class<?> cls) {
        if (!SIMPLE.contains(cls) && !cls.isEnum()) return null;

        if (cls == String.class || CharSequence.class.isAssignableFrom(cls)) return Map.of("type", "string");
        if (cls == Boolean.class || cls == boolean.class) return Map.of("type", "boolean");
        if (cls == Integer.class || cls == int.class || cls == Long.class || cls == long.class ||
                cls == Short.class || cls == short.class || cls == Byte.class || cls == byte.class) {
            return Map.of("type", "integer");
        }
        if (cls == Float.class || cls == float.class || cls == Double.class || cls == double.class) {
            return Map.of("type", "number");
        }
        if (cls == UUID.class) return Map.of("type", "string", "format", "uuid");
        if (cls == LocalDate.class) return Map.of("type", "string", "format", "date");
        if (cls == LocalDateTime.class || cls == OffsetDateTime.class || cls == Instant.class)
            return Map.of("type", "string", "format", "date-time");
        if (cls.isEnum()) {
            Object[] constants = cls.getEnumConstants();
            List<String> values = new ArrayList<>(constants.length);
            for (Object c : constants) values.add(String.valueOf(c));
            return Map.of("type", "string", "enum", values);
        }
        return null;
    }


static Class<?> rawType(Type type) {
    if (type instanceof Class<?> c) return c;
    if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) return c;
    if (type instanceof GenericArrayType a) {
        Class<?> component = rawType(a.getGenericComponentType());
        return component == null ? null : Array.newInstance(component, 0).getClass();
    }
    return null;
}
}
