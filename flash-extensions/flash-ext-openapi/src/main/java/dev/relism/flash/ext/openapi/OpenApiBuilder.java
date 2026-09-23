package dev.relism.flash.ext.openapi;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.models.BodyHandler;
import dev.relism.flash.routing.Consumes;
import dev.relism.flash.routing.Produces;
import dev.relism.flash.routing.Route;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the OpenAPI document from what the handlers already say about themselves.
 *
 * <p>A route is documented whether or not it carries annotations: its path parameters come from
 * the path, its request body from the handler's own body type, its response schema from what
 * {@code handle} returns, and its error bodies from the one shape Flash answers failures with.
 * Annotations add what code cannot say — a summary, an example, a second status — and never have
 * to repeat what it can.
 *
 * <p>A response that several operations share is written once under {@code components} and
 * referenced, so the security and rate-limiting answers appear once rather than on every path.
 */
public final class OpenApiBuilder {

    private static final String OPENAPI_VERSION = "3.0.3";
    private static final String ERROR_SCHEMA = "Error";
    private static final String ERROR_REF = "#/components/schemas/" + ERROR_SCHEMA;
    private static final String RESPONSE_REF = "#/components/responses/";

    /** What {@code AbstractRouter} answers every failure with: one object, everywhere. */
    private static final Map<String, Object> ERROR_SHAPE = Map.of(
            "type", "object",
            "properties", Map.of("error", Map.of("type", "string"), "status", Map.of("type", "integer")),
            "required", List.of("error", "status"));

    private String title = "API";
    private String version = "1.0.0";
    private String description = "";

    private final Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
    private final Map<String, Map<String, Class<?>>> operationHandlers = new LinkedHashMap<>();
    private final Schemas schemas = new Schemas();
    private OpenApiContributorRegistry contributorRegistry;
    private boolean errorsDocumented;
    private int revision;
    private int builtRevision = -1;
    private Map<String, Object> cachedSpec;

    public OpenApiBuilder title(String title) { this.title = title; return this; }
    public OpenApiBuilder version(String version) { this.version = version; return this; }
    public OpenApiBuilder description(String description) { this.description = description; return this; }
    void setContributorRegistry(OpenApiContributorRegistry registry) { this.contributorRegistry = registry; }

    /**
     * Documents one route. {@code op} is optional: a route without it is still an operation.
     * A handler marked {@link Undocumented} is left out entirely.
     */
    public void addOperation(Route route, ApiOperation op, Class<?> handlerClass) {
        if (handlerClass.isAnnotationPresent(Undocumented.class)) return;

        String path = normalizePath(route.path());
        String method = route.method().name().toLowerCase(Locale.ROOT);

        Map<String, Object> operation = new LinkedHashMap<>();
        if (op != null) {
            if (!op.operationId().isEmpty()) operation.put("operationId", op.operationId());
            if (!op.summary().isEmpty()) operation.put("summary", op.summary());
            if (!op.description().isEmpty()) operation.put("description", op.description());
            if (op.tags().length > 0) operation.put("tags", List.of(op.tags()));
            if (op.deprecated()) operation.put("deprecated", true);
        }

        parameters(operation, handlerClass, route);
        requestBody(operation, handlerClass);
        responses(operation, handlerClass);

        paths.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(method, operation);
        operationHandlers.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(method, handlerClass);
        revision++;
    }

    public Map<String, Object> build() {
        int current = revision;
        Map<String, Object> cached = cachedSpec;
        if (cached != null && builtRevision == current) return cached;

        List<OpenApiContributor> contributors = contributors();
        Map<String, Object> renderedPaths = new LinkedHashMap<>();
        for (var path : paths.entrySet()) {
            Map<String, Class<?>> handlers = operationHandlers.getOrDefault(path.getKey(), Map.of());
            Map<String, Object> pathItem = new LinkedHashMap<>();
            for (var method : path.getValue().entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> declared = (Map<String, Object>) method.getValue();
                Map<String, Object> operation = new LinkedHashMap<>(declared);
                Class<?> handler = handlers.get(method.getKey());
                if (handler != null && !contributors.isEmpty()) applyContributorSecurity(operation, handler, contributors);
                pathItem.put(method.getKey(), operation);
            }
            renderedPaths.put(path.getKey(), pathItem);
        }

        Map<String, Object> sharedResponses = hoistSharedResponses(renderedPaths);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("version", version);
        if (!description.isEmpty()) info.put("description", description);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", OPENAPI_VERSION);
        spec.put("info", info);
        spec.put("paths", renderedPaths);

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> renderedSchemas = new LinkedHashMap<>(schemas.render());
        if (errorsDocumented) renderedSchemas.put(ERROR_SCHEMA, ERROR_SHAPE);
        if (!renderedSchemas.isEmpty()) components.put("schemas", renderedSchemas);
        if (!sharedResponses.isEmpty()) components.put("responses", sharedResponses);
        for (OpenApiContributor contributor : contributors) {
            Map<String, Object> contributed = contributor.componentContributions();
            if (contributed != null && !contributed.isEmpty()) deepMergeLastWins(components, contributed);
        }
        if (!components.isEmpty()) spec.put("components", components);

        cachedSpec = spec;
        builtRevision = current;
        return spec;
    }

    // ── Parameters ────────────────────────────────────────────────────────────

    private void parameters(Map<String, Object> operation, Class<?> handlerClass, Route route) {
        List<Map<String, Object>> parameters = new ArrayList<>();

        String path = route.path();
        for (int open = path.indexOf('{'); open >= 0; open = path.indexOf('{', open + 1)) {
            int close = path.indexOf('}', open);
            if (close < 0) break;
            parameters.add(new LinkedHashMap<>(Map.of(
                    "name", path.substring(open + 1, close),
                    "in", "path",
                    "required", true,
                    "schema", Map.of("type", "string"))));
            open = close;
        }

        for (Parameter declared : handlerClass.getAnnotationsByType(Parameter.class)) {
            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("name", declared.name());
            parameter.put("in", declared.in().wireValue());
            parameter.put("required", declared.required());
            if (!declared.description().isEmpty()) parameter.put("description", declared.description());
            if (!declared.style().isEmpty()) parameter.put("style", declared.style());
            if (declared.explode()) parameter.put("explode", true);
            if (declared.allowEmptyValue()) parameter.put("allowEmptyValue", true);

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", declared.type().wireValue());
            if (!declared.example().isEmpty()) schema.put("example", declared.example());
            parameter.put("schema", schema);
            if (declared.examples().length > 0) parameter.put("examples", examples(declared.examples()));
            parameters.add(parameter);
        }

        if (!parameters.isEmpty()) operation.put("parameters", parameters);
    }

    private static Map<String, Object> examples(String[] values) {
        Map<String, Object> examples = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) examples.put("example" + (i + 1), Map.of("value", values[i]));
        return examples;
    }

    // ── Request body ──────────────────────────────────────────────────────────

    /** From {@link RequestBody}, or from the body type the handler declares in its own signature. */
    private void requestBody(Map<String, Object> operation, Class<?> handlerClass) {
        RequestBody declared = handlerClass.getAnnotation(RequestBody.class);
        Class<?> type = declared != null ? declared.value() : BodyHandler.bodyTypeOf(handlerClass);
        if (type == null || type == Void.class || type == Object.class) return;

        Consumes consumes = handlerClass.getAnnotation(Consumes.class);
        ContentType contentType = declared != null ? declared.contentType()
                : consumes != null ? consumes.value() : ContentType.JSON;

        Map<String, Object> schema = schemas.referenceFor(type);
        if (declared != null && declared.array()) schema = arrayOf(schema);

        Map<String, Object> body = new LinkedHashMap<>();
        if (declared != null && !declared.description().isEmpty()) body.put("description", declared.description());
        body.put("required", declared == null || declared.required());
        body.put("content", Map.of(mediaTypeOf(contentType), Map.of("schema", schema)));
        operation.put("requestBody", body);
    }

    // ── Responses ─────────────────────────────────────────────────────────────

    private void responses(Map<String, Object> operation, Class<?> handlerClass) {
        APIResponse[] declared = handlerClass.getAnnotationsByType(APIResponse.class);
        Map<Integer, Map<String, Object>> byStatus = new LinkedHashMap<>();
        Set<Integer> declaredStatuses = new HashSet<>();

        for (APIResponse response : declared) {
            int status = parseStatus(response.responseCode());
            declaredStatuses.add(status);
            byStatus.put(status, response(status, response, handlerClass));
        }
        if (byStatus.isEmpty()) byStatus.put(200, inferredResponse(handlerClass));

        applyContributorResponses(byStatus, handlerClass, declaredStatuses);

        Map<String, Object> responses = new LinkedHashMap<>();
        byStatus.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> responses.put(String.valueOf(entry.getKey()), entry.getValue()));
        operation.put("responses", responses);
    }

    private Map<String, Object> response(int status, APIResponse declared, Class<?> handlerClass) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("description", declared.description().isEmpty() ? reasonFor(status) : declared.description());

        Content content = declared.content();
        Map<String, Object> schema = schemaFor(content, status, handlerClass);
        if (schema == null) return response;   // nothing to describe: a response with no body

        Map<String, Object> media = new LinkedHashMap<>();
        media.put("schema", schema);
        if (!content.example().isEmpty()) media.put("example", content.example());
        response.put("content", Map.of(mediaTypeOf(producedBy(handlerClass)), media));
        return response;
    }

    /** Explicit first, then what the handler returns for a success, and the error shape for a failure. */
    private Map<String, Object> schemaFor(Content content, int status, Class<?> handlerClass) {
        if (content.schema() != Void.class) {
            Map<String, Object> schema = schemas.referenceFor(content.schema());
            return content.array() ? arrayOf(schema) : schema;
        }
        if (status >= 400) return errorSchema();

        Map<String, Object> inferred = returnSchema(handlerClass);
        if (inferred == null) return null;
        return content.array() && !"array".equals(inferred.get("type")) ? arrayOf(inferred) : inferred;
    }

    /** A route that documents nothing still answers something: describe what it returns. */
    private Map<String, Object> inferredResponse(Class<?> handlerClass) {
        Map<String, Object> response = new LinkedHashMap<>(Map.of("description", reasonFor(200)));
        Map<String, Object> schema = returnSchema(handlerClass);
        if (schema != null) {
            response.put("content", Map.of(mediaTypeOf(producedBy(handlerClass)), Map.of("schema", schema)));
        }
        return response;
    }

    /**
     * What this handler answers in. {@code @Consumes} says what it reads, which is a different
     * question: taking a JSON body does not make the answer JSON.
     */
    private static ContentType producedBy(Class<?> handlerClass) {
        Produces produces = handlerClass.getAnnotation(Produces.class);
        return produces == null ? ContentType.JSON : produces.value();
    }

    /** The schema of whatever {@code handle} gives back, or null when it says nothing useful. */
    private Map<String, Object> returnSchema(Class<?> handlerClass) {
        Type returned = returnTypeOf(handlerClass);
        Class<?> raw = Schemas.rawType(returned);
        if (raw == null || raw == Object.class || raw == Void.class || raw == void.class) return null;
        if (raw.getName().equals("dev.relism.flash.models.Response")) return null;

        Map<String, Object> schema = schemas.schemaForType(returned);
        return schema == null || schema.isEmpty() ? null : schema;
    }

    /**
     * The {@code handle} a handler writes itself, not the one its base class fixes: a handler that
     * takes a body implements the three-argument one, and that is where its return type is.
     */
    private static Type returnTypeOf(Class<?> handlerClass) {
        for (Class<?> current = handlerClass; current != null && current != Object.class; current = current.getSuperclass()) {
            Method found = null;
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("handle") || method.isBridge() || method.isSynthetic()) continue;
                if (found == null || method.getParameterCount() > found.getParameterCount()) found = method;
            }
            if (found != null) return found.getGenericReturnType();
        }
        return null;
    }

    private Map<String, Object> errorSchema() {
        errorsDocumented = true;
        return Map.of("$ref", ERROR_REF);
    }

    // ── Contributors ──────────────────────────────────────────────────────────

    private List<OpenApiContributor> contributors() {
        return contributorRegistry != null ? contributorRegistry.contributors() : List.of();
    }

    private void applyContributorResponses(Map<Integer, Map<String, Object>> byStatus, Class<?> handlerClass,
                                           Set<Integer> declaredStatuses) {
        for (OpenApiContributor contributor : contributors()) {
            OpenApiOperationContribution contribution = contributor.operationFor(handlerClass);
            if (contribution == null) continue;

            for (var contributed : contribution.responses().entrySet()) {
                if (contributed.getValue() == null) continue;
                int status = contributed.getKey();
                Map<String, Object> response = byStatus.computeIfAbsent(status, s -> new LinkedHashMap<>());
                merge(response, contributed.getValue(), status, declaredStatuses.contains(status));
            }

            OpenApiResponseContribution everywhere = contribution.allResponses();
            if (everywhere == null) continue;
            for (var response : byStatus.entrySet()) {
                merge(response.getValue(), everywhere, response.getKey(), declaredStatuses.contains(response.getKey()));
            }
        }
    }

    private void merge(Map<String, Object> response, OpenApiResponseContribution contributed, int status, boolean declared) {
        String description = contributed.description();
        if (!declared && description != null && !description.isBlank()) response.put("description", description);

        Map<String, Map<String, Object>> headers = contributed.headers();
        if (!headers.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> target = (Map<String, Object>) response.computeIfAbsent("headers", h -> new LinkedHashMap<>());
            headers.forEach((name, header) -> target.put(name, new LinkedHashMap<>(header)));
        }
        if (!response.containsKey("description")) response.put("description", reasonFor(status));
        // A status a contributor added is a failure Flash answers in its own shape.
        if (status >= 400 && !response.containsKey("content")) {
            response.put("content", Map.of(mediaTypeOf(ContentType.JSON), Map.of("schema", errorSchema())));
        }   // Flash answers a failure in JSON whatever the route produces
    }

    private void applyContributorSecurity(Map<String, Object> operation, Class<?> handlerClass,
                                          List<OpenApiContributor> contributors) {
        for (OpenApiContributor contributor : contributors) {
            OpenApiOperationContribution contribution = contributor.operationFor(handlerClass);
            if (contribution == null || contribution.security().isEmpty()) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, List<String>>> security =
                    (List<Map<String, List<String>>>) operation.computeIfAbsent("security", s -> new ArrayList<>());
            security.addAll(contribution.security());
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepMergeLastWins(Map<String, Object> target, Map<String, Object> incoming) {
        incoming.forEach((key, value) -> {
            Object existing = target.get(key);
            if (existing instanceof Map<?, ?> from && value instanceof Map<?, ?> to) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) from);
                deepMergeLastWins(merged, (Map<String, Object>) to);
                target.put(key, merged);
            } else {
                target.put(key, value);
            }
        });
    }

    // ── Shared responses ──────────────────────────────────────────────────────

    /**
     * Whatever answer more than one operation gives identically is written once under
     * {@code components.responses} and referenced. Authentication and rate limiting say the same
     * thing on every route they guard; the document should say it once.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> hoistSharedResponses(Map<String, Object> renderedPaths) {
        Map<Object, Integer> seen = new HashMap<>();
        for (Object pathItem : renderedPaths.values()) {
            for (Object operation : ((Map<String, Object>) pathItem).values()) {
                Map<String, Object> responses = (Map<String, Object>) ((Map<String, Object>) operation).get("responses");
                responses.forEach((status, response) -> seen.merge(key(status, response), 1, Integer::sum));
            }
        }

        Map<Object, String> names = new LinkedHashMap<>();
        Map<String, Object> shared = new LinkedHashMap<>();
        for (Object pathItem : renderedPaths.values()) {
            for (Object operation : ((Map<String, Object>) pathItem).values()) {
                Map<String, Object> responses = (Map<String, Object>) ((Map<String, Object>) operation).get("responses");
                for (var response : responses.entrySet()) {
                    Object key = key(response.getKey(), response.getValue());
                    if (seen.getOrDefault(key, 0) < 2) continue;

                    String name = names.get(key);
                    if (name == null) {
                        name = uniqueName(reasonFor(Integer.parseInt(response.getKey())), shared);
                        names.put(key, name);
                        shared.put(name, response.getValue());
                    }
                    response.setValue(Map.of("$ref", RESPONSE_REF + name));
                }
            }
        }
        return shared;
    }

    private static Object key(String status, Object response) {
        return status + response;
    }

    private static String uniqueName(String reason, Map<String, Object> taken) {
        String base = reason.isEmpty() ? "Response" : reason.replace(" ", "");
        String name = base;
        for (int i = 2; taken.containsKey(name); i++) name = base + i;
        return name;
    }

    // ── Odds and ends ─────────────────────────────────────────────────────────

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static int parseStatus(String code) {
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid APIResponse.responseCode: " + code);
        }
    }

    private static String reasonFor(int status) {
        String reason = HttpStatus.reasonForCode(status);
        return reason == null ? "" : reason;
    }

    private static String mediaTypeOf(ContentType type) {
        byte[] bytes = type.getBytes();
        return bytes.length == 0 ? "application/octet-stream" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String normalizePath(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        while (normalized.startsWith("//")) normalized = normalized.substring(1);
        return normalized;
    }

    /** The route a handler class declares, through {@code @Route} or any shorthand carrying it. */
    static Route routeOf(Class<?> cls) {
        Route direct = cls.getAnnotation(Route.class);
        if (direct != null) return direct;

        for (Annotation annotation : cls.getAnnotations()) {
            Route meta = annotation.annotationType().getAnnotation(Route.class);
            if (meta == null) continue;
            String path = pathOf(annotation);
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

    private static String pathOf(Annotation annotation) {
        try {
            return annotation.annotationType().getMethod("value").invoke(annotation) instanceof String path ? path : null;
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }
}
