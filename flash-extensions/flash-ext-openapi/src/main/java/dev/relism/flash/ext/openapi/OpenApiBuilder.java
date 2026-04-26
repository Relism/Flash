package dev.relism.flash.ext.openapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.Route;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * OpenAPI document assembler.
 */
public final class OpenApiBuilder {

    private static final String OPENAPI_VERSION = "3.0.3";

    private String title = "API";
    private String version = "1.0.0";
    private String description = "";

    private final Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
    private final Map<String, Map<String, Class<?>>> operationHandlers = new LinkedHashMap<>();
    private final SchemaRegistry schemas = new SchemaRegistry();
    private OpenApiContributorRegistry contributorRegistry;
    private int revision;
    private int builtRevision = -1;
    private Map<String, Object> cachedSpec;

    public OpenApiBuilder title(String title) { this.title = title; return this; }
    public OpenApiBuilder version(String version) { this.version = version; return this; }
    public OpenApiBuilder description(String description) { this.description = description; return this; }
    void setContributorRegistry(OpenApiContributorRegistry registry) { this.contributorRegistry = registry; }

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

        List<OpenApiContributor> contributors = contributorRegistry != null
                ? contributorRegistry.contributors() : List.of();

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
                    applyContributorOperation(op, handler, contributors);
                }
                renderedPathItem.put(methodEntry.getKey(), op);
            }
            renderedPaths.put(pathEntry.getKey(), renderedPathItem);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", OPENAPI_VERSION);
        spec.put("info", info);
        spec.put("paths", renderedPaths);

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> renderedSchemas = schemas.render();
        if (!renderedSchemas.isEmpty()) components.put("schemas", renderedSchemas);
        if (!contributors.isEmpty()) applyContributorComponents(components, contributors);
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

        for (Parameter ann : cls.getAnnotationsByType(Parameter.class)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", ann.name());
            p.put("in", ann.in().wireValue());
            p.put("required", ann.required());
            if (!ann.description().isEmpty()) p.put("description", ann.description());
            if (!ann.style().isEmpty()) p.put("style", ann.style());
            if (ann.explode()) p.put("explode", true);
            if (ann.allowEmptyValue()) p.put("allowEmptyValue", true);

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", ann.type().wireValue());
            if (!ann.example().isEmpty()) schema.put("example", ann.example());
            p.put("schema", schema);
            if (ann.examples().length > 0) p.put("examples", toExamples(ann.examples()));
            params.add(p);
        }

        if (!params.isEmpty()) op.put("parameters", params);
    }

    private void buildResponses(Map<String, Object> op, Class<?> cls) {
        APIResponse[] anns = cls.getAnnotationsByType(APIResponse.class);
        Map<Integer, Map<String, Object>> responseByCode = new LinkedHashMap<>();

        for (APIResponse ann : anns) {
            int code = parseStatus(ann.responseCode());
            responseByCode.put(code, buildAnnotatedResponse(code, ann, cls));
        }

        if (responseByCode.isEmpty()) {
            responseByCode.put(200, Map.of("description", "OK"));
        }

        applyContributorResponses(responseByCode, cls);

        Map<String, Object> responses = new LinkedHashMap<>();
        responseByCode.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(e -> responses.put(String.valueOf(e.getKey()), e.getValue()));
        op.put("responses", responses);
    }

    private List<OpenApiContributor> contributors() {
        return contributorRegistry != null ? contributorRegistry.contributors() : List.of();
    }

    private void applyContributorResponses(Map<Integer, Map<String, Object>> responseByCode, Class<?> handlerClass) {
        APIResponse[] manual = handlerClass.getAnnotationsByType(APIResponse.class);
        Set<Integer> manualStatusCodes = new HashSet<>();
        for (APIResponse ann : manual) {
            manualStatusCodes.add(parseStatus(ann.responseCode()));
        }

        for (OpenApiContributor contributor : contributors()) {
            OpenApiOperationContribution contribution = contributor.operationFor(handlerClass);
            if (contribution == null) continue;

            for (Map.Entry<Integer, OpenApiResponseContribution> entry : contribution.responses().entrySet()) {
                int status = entry.getKey();
                OpenApiResponseContribution responseContribution = entry.getValue();
                if (responseContribution == null) continue;

                Map<String, Object> response = responseByCode.computeIfAbsent(status, __ -> new LinkedHashMap<>());
                mergeContributorResponse(response, responseContribution, status, manualStatusCodes);
            }

            OpenApiResponseContribution allResponses = contribution.allResponses();
            if (allResponses != null) {
                for (Map.Entry<Integer, Map<String, Object>> entry : responseByCode.entrySet()) {
                    mergeContributorResponse(entry.getValue(), allResponses, entry.getKey(), manualStatusCodes);
                }
            }
        }
    }

    private static void mergeContributorResponse(Map<String, Object> response,
                                                 OpenApiResponseContribution contribution,
                                                 int status,
                                                 Set<Integer> manualStatusCodes) {
        String desc = contribution.description();
        if (!manualStatusCodes.contains(status) && desc != null && !desc.isBlank()) {
            response.put("description", desc);
        }

        Map<String, Map<String, Object>> headerContributions = contribution.headers();
        if (!headerContributions.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) response.computeIfAbsent("headers", __ -> new LinkedHashMap<>());
            for (Map.Entry<String, Map<String, Object>> h : headerContributions.entrySet()) {
                headers.put(h.getKey(), new LinkedHashMap<>(h.getValue()));
            }
        }

        if (!response.containsKey("description")) {
            response.put("description", defaultDescription(status));
        }
    }

    private static void applyContributorComponents(Map<String, Object> components, List<OpenApiContributor> contributors) {
        for (OpenApiContributor contributor : contributors) {
            Map<String, Object> c = contributor.componentContributions();
            if (c == null || c.isEmpty()) continue;
            deepMergeLastWins(components, c);
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepMergeLastWins(Map<String, Object> target, Map<String, Object> incoming) {
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            Object existing = target.get(e.getKey());
            Object value = e.getValue();
            if (existing instanceof Map<?, ?> em && value instanceof Map<?, ?> vm) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) em);
                deepMergeLastWins(merged, (Map<String, Object>) vm);
                target.put(e.getKey(), merged);
            } else {
                target.put(e.getKey(), value);
            }
        }
    }

    private void applyContributorOperation(Map<String, Object> op, Class<?> handlerClass, List<OpenApiContributor> contributors) {
        for (OpenApiContributor contributor : contributors) {
            OpenApiOperationContribution contribution = contributor.operationFor(handlerClass);
            if (contribution == null || contribution.isEmpty()) continue;

            if (!contribution.security().isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, List<String>>> security = (List<Map<String, List<String>>>) op
                        .computeIfAbsent("security", __ -> new ArrayList<>());
                security.addAll(contribution.security());
            }
        }
    }

    private Map<String, Object> buildAnnotatedResponse(int code, APIResponse ann, Class<?> handlerClass) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description", ann.description().isEmpty() ? defaultDescription(code) : ann.description());

        Content content = ann.content();
        if (content.contentType() == ContentType.NONE) return out;

        Map<String, Object> schema = resolveResponseSchema(content, handlerClass);
        if (schema == null || schema.isEmpty()) return out;

        out.put("content", Map.of(mediaTypeOf(content.contentType()), Map.of("schema", schema)));
        return out;
    }

    private static int parseStatus(String code) {
        try {
            return Integer.parseInt(code.trim());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid APIResponse.responseCode: " + code);
        }
    }

    private Map<String, Object> resolveResponseSchema(Content content, Class<?> handlerClass) {
        if (content.schema() != Void.class) {
            Map<String, Object> base = schemas.referenceFor(content.schema());
            return content.array() ? asArraySchema(base) : base;
        }

        try {
            Method handle = handlerClass.getMethod("handle", Request.class, Response.class);
            Type ret = handle.getGenericReturnType();
            Class<?> raw = rawType(ret);
            if (raw == null || raw == Object.class || raw == Response.class || raw == Void.class || raw == void.class)
                return null;

            Map<String, Object> inferred = schemas.schemaForType(ret);
            if (inferred == null || inferred.isEmpty()) return null;
            if (content.array() && !"array".equals(inferred.get("type"))) return asArraySchema(inferred);
            return inferred;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Map<String, Object> asArraySchema(Map<String, Object> itemSchema) {
        return Map.of("type", "array", "items", itemSchema);
    }

    private static String mediaTypeOf(ContentType type) {
        byte[] bytes = type.getBytes();
        return bytes.length == 0 ? "application/octet-stream" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> toExamples(String[] examples) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < examples.length; i++) {
            out.put("ex" + (i + 1), Map.of("value", examples[i]));
        }
        return out;
    }

    private static String normalizePath(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        while (normalized.startsWith("//")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String defaultDescription(int status) {
        String reason = HttpStatus.reasonForCode(status);
        return reason == null ? "" : reason;
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
            JsonIgnoreProperties ignoredType = cls.getAnnotation(JsonIgnoreProperties.class);
            Set<String> ignored = ignoredType == null
                    ? Set.of()
                    : new HashSet<>(Arrays.asList(ignoredType.value()));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "object");
            if (typeSchema != null) applySchemaHints(out, typeSchema);

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
    }

    static Route routeOf(Class<?> cls) {
        Route direct = cls.getAnnotation(Route.class);
        if (direct != null) return direct;
        for (Annotation ann : cls.getAnnotations()) {
            Route meta = ann.annotationType().getAnnotation(Route.class);
            if (meta == null) continue;
            String path = readPathValue(ann);
            if (path == null) continue;
            HttpMethod method = meta.method();
            return new Route() {
                @Override public HttpMethod method() { return method; }
                @Override public String path() { return path; }
                @Override public Class<? extends Annotation> annotationType() { return Route.class; }
            };
        }
        return null;
    }

    private static String readPathValue(Annotation ann) {
        try {
            Object v = ann.annotationType().getMethod("value").invoke(ann);
            return v instanceof String s ? s : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
