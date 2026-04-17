package dev.relism.ext.openapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import dev.relism.routing.Route;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/**
 * OpenAPI document assembler.
 *
 * Collects operation metadata at route registration time and renders an OpenAPI 3.0.3 map.
 * Response schemas are resolved automatically into components.schemas.
 */
public final class OpenApiBuilder {

    private String title = "API";
    private String version = "1.0.0";
    private String description = "";

    private final Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
    private final Map<String, Map<String, Class<?>>> operationHandlers = new LinkedHashMap<>();
    private final SchemaRegistry schemas = new SchemaRegistry();
    private OpenApiSecurityRegistry securityRegistry;
    // Build cache: OpenAPI is rendered only when the document revision changes.
    private int revision;
    private int builtRevision = -1;
    private Map<String, Object> cachedSpec;

    public OpenApiBuilder title(String title) { this.title = title; return this; }
    public OpenApiBuilder version(String version) { this.version = version; return this; }
    public OpenApiBuilder description(String description) { this.description = description; return this; }
    void setSecurityRegistry(OpenApiSecurityRegistry registry) { this.securityRegistry = registry; }

    public void addOperation(Route route, ApiOperation op, Class<?> handlerClass) {
        String path = normalizePath(route.path());
        String method = route.method().name().toLowerCase(Locale.ROOT);

        Map<String, Object> operation = new LinkedHashMap<>();
        if (!op.operationId().isEmpty()) operation.put("operationId", op.operationId());
        if (!op.summary().isEmpty()) operation.put("summary", op.summary());
        if (!op.description().isEmpty()) operation.put("description", op.description());
        if (op.tags().length > 0) operation.put("tags", Arrays.asList(op.tags()));
        if (op.deprecated()) operation.put("deprecated", true);

        buildParameters(operation, handlerClass, route);
        buildResponses(operation, handlerClass);

        paths.computeIfAbsent(path, k -> new LinkedHashMap<>()).put(method, operation);
        operationHandlers.computeIfAbsent(path, k -> new LinkedHashMap<>()).put(method, handlerClass);
        revision++;
    }

    public Map<String, Object> build() {
        int r = revision;
        Map<String, Object> cached = cachedSpec;
        if (cached != null && builtRevision == r) return cached;

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("version", version);
        if (!description.isEmpty()) info.put("description", description);

        List<OpenApiSecurityContributor> contributors = securityRegistry != null
                ? securityRegistry.contributors() : List.of();

        Map<String, Object> renderedPaths = new LinkedHashMap<>();
        for (var pathEntry : paths.entrySet()) {
            Map<String, Object> renderedPathItem = new LinkedHashMap<>();
            Map<String, Class<?>> handlers = operationHandlers.getOrDefault(pathEntry.getKey(), Map.of());
            for (var methodEntry : pathEntry.getValue().entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> original = (Map<String, Object>) methodEntry.getValue();
                Map<String, Object> op = new LinkedHashMap<>(original);
                Class<?> handler = handlers.get(methodEntry.getKey());
                if (handler != null && !contributors.isEmpty()) {
                    List<Map<String, List<String>>> security = buildOperationSecurity(contributors, handler);
                    if (!security.isEmpty()) op.put("security", security);
                }
                renderedPathItem.put(methodEntry.getKey(), op);
            }
            renderedPaths.put(pathEntry.getKey(), renderedPathItem);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", info);
        spec.put("paths", renderedPaths);

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> renderedSchemas = schemas.render();
        if (!renderedSchemas.isEmpty()) components.put("schemas", renderedSchemas);
        if (!contributors.isEmpty()) {
            Map<String, Object> securitySchemes = new LinkedHashMap<>();
            for (OpenApiSecurityContributor c : contributors) securitySchemes.put(c.schemeName(), c.schemeDefinition());
            components.put("securitySchemes", securitySchemes);
        }
        if (!components.isEmpty()) spec.put("components", components);

        cachedSpec = spec;
        builtRevision = r;
        return spec;
    }

    private void buildParameters(Map<String, Object> op, Class<?> cls, Route route) {
        List<Map<String, Object>> params = new ArrayList<>();

        String path = route.path();
        int i = 0;
        while (i < path.length()) {
            int open = path.indexOf('{', i);
            if (open < 0) break;
            int close = path.indexOf('}', open);
            if (close < 0) break;
            String name = path.substring(open + 1, close);
            params.add(new LinkedHashMap<>(Map.of(
                    "name", name,
                    "in", "path",
                    "required", true,
                    "schema", Map.of("type", "string")
            )));
            i = close + 1;
        }

        for (ApiParam ann : cls.getAnnotationsByType(ApiParam.class)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", ann.name());
            p.put("in", ann.in());
            p.put("required", ann.required());
            if (!ann.description().isEmpty()) p.put("description", ann.description());
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", ann.type());
            if (!ann.example().isEmpty()) schema.put("example", ann.example());
            p.put("schema", schema);
            params.add(p);
        }

        if (!params.isEmpty()) op.put("parameters", params);
    }

    private void buildResponses(Map<String, Object> op, Class<?> cls) {
        ApiResponse[] anns = cls.getAnnotationsByType(ApiResponse.class);
        Map<String, Object> responses = new LinkedHashMap<>();

        if (anns.length == 0) {
            responses.put("200", Map.of("description", "OK"));
            op.put("responses", responses);
            return;
        }

        for (ApiResponse ann : anns) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("description", ann.description().isEmpty() ? httpPhrase(ann.status()) : ann.description());

            Class<?> schemaType = resolveSchemaType(ann, cls);
            if (schemaType != null && schemaType != Void.class) {
                r.put("content", Map.of(
                        "application/json", Map.of("schema", schemas.referenceFor(schemaType))
                ));
            }
            responses.put(String.valueOf(ann.status()), r);
        }

        op.put("responses", responses);
    }

    private static Class<?> resolveSchemaType(ApiResponse ann, Class<?> handlerClass) {
        if (ann.schema() != Void.class) return ann.schema();
        if (!ann.useReturnType()) return null;
        try {
            Method handle = handlerClass.getMethod("handle", dev.relism.models.Request.class, dev.relism.models.Response.class);
            Type ret = handle.getGenericReturnType();
            Class<?> raw = rawType(ret);
            if (raw == null || raw == Object.class || raw == dev.relism.models.Response.class || raw == Void.class || raw == void.class)
                return null;
            return raw;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String httpPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 500 -> "Internal Server Error";
            default -> "";
        };
    }

    private static List<Map<String, List<String>>> buildOperationSecurity(List<OpenApiSecurityContributor> contributors,
                                                                          Class<?> handlerClass) {
        List<Map<String, List<String>>> security = new ArrayList<>();
        for (OpenApiSecurityContributor c : contributors) {
            List<String> scopes = c.requiredFor(handlerClass);
            if (scopes != null) security.add(Map.of(c.schemeName(), scopes));
        }
        return security;
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) return c;
        if (type instanceof GenericArrayType a) {
            Class<?> component = rawType(a.getGenericComponentType());
            return component == null ? null : Array.newInstance(component, 0).getClass();
        }
        return null;
    }

    private static final class SchemaRegistry {
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
            JsonIgnoreProperties ignoredType = cls.getAnnotation(JsonIgnoreProperties.class);
            Set<String> ignored = ignoredType == null
                    ? Set.of()
                    : new HashSet<>(Arrays.asList(ignoredType.value()));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "object");
            if (typeSchema != null && !typeSchema.description().isEmpty()) out.put("description", typeSchema.description());

            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();

            for (Field f : cls.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                if (f.isAnnotationPresent(JsonIgnore.class)) continue;
                if (ignored.contains(f.getName())) continue;

                String name = f.getName();
                JsonProperty jp = f.getAnnotation(JsonProperty.class);
                if (jp != null && !jp.value().isEmpty()) name = jp.value();

                Schema ps = f.getAnnotation(Schema.class);
                SchemaProperty sp = f.getAnnotation(SchemaProperty.class);
                ArraySchema array = f.getAnnotation(ArraySchema.class);
                if ((ps != null && ps.hidden()) || (sp != null && sp.hidden())) continue;

                if (sp != null && !sp.name().isEmpty()) name = sp.name();

                Map<String, Object> property = new LinkedHashMap<>(schemaFor(f.getGenericType()));
                if (ps != null) applySchemaHints(property, ps);
                if (sp != null) applySchemaHints(property, sp);
                if (array != null) applyArrayHints(property, array);
                if (jp != null) {
                    if (jp.access() == Access.READ_ONLY) property.put("readOnly", true);
                    if (jp.access() == Access.WRITE_ONLY) property.put("writeOnly", true);
                }

                properties.put(name, property);
                if ((ps != null && ps.required()) || (sp != null && sp.required()) || (jp != null && jp.required())) required.add(name);
            }

            if (!properties.isEmpty()) out.put("properties", properties);
            if (!required.isEmpty()) out.put("required", required);
            return out;
        }

        private static void applySchemaHints(Map<String, Object> property, Schema schema) {
            if (!schema.description().isEmpty()) property.put("description", schema.description());
            if (!schema.format().isEmpty()) property.put("format", schema.format());
            if (!schema.example().isEmpty()) property.put("example", schema.example());
            if (schema.nullable()) property.put("nullable", true);
        }

        private static void applySchemaHints(Map<String, Object> property, SchemaProperty schema) {
            if (!schema.description().isEmpty()) property.put("description", schema.description());
            if (!schema.format().isEmpty()) property.put("format", schema.format());
            if (!schema.example().isEmpty()) property.put("example", schema.example());
            if (schema.nullable()) property.put("nullable", true);
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

        private Map<String, Object> simpleSchema(Class<?> cls) {
            if (cls == String.class || cls == CharSequence.class || cls == UUID.class) return Map.of("type", "string");
            if (cls == boolean.class || cls == Boolean.class) return Map.of("type", "boolean");
            if (cls == byte.class || cls == Byte.class || cls == short.class || cls == Short.class ||
                    cls == int.class || cls == Integer.class) return Map.of("type", "integer", "format", "int32");
            if (cls == long.class || cls == Long.class) return Map.of("type", "integer", "format", "int64");
            if (cls == float.class || cls == Float.class) return Map.of("type", "number", "format", "float");
            if (cls == double.class || cls == Double.class) return Map.of("type", "number", "format", "double");
            if (cls == LocalDate.class) return Map.of("type", "string", "format", "date");
            if (cls == LocalDateTime.class || cls == OffsetDateTime.class || cls == Instant.class)
                return Map.of("type", "string", "format", "date-time");
            if (cls.isEnum()) {
                Object[] constants = cls.getEnumConstants();
                List<String> values = new ArrayList<>(constants.length);
                for (Object c : constants) values.add(((Enum<?>) c).name());
                return Map.of("type", "string", "enum", values);
            }
            if (!SIMPLE.contains(cls) && cls.getName().startsWith("java.")) return Map.of("type", "string");
            return null;
        }
    }
}
