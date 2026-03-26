package dev.relism.ext.openapi;

import dev.relism.routing.Route;

import java.util.*;

/**
 * Accumulates OpenAPI 3.0 operations and builds the spec document as a plain
 * {@code Map} for Jackson to serialize. Operations are added at registration time
 * via {@link OpenApiExtension}'s {@link dev.relism.AnnotationProcessor}.
 */
public class OpenApiBuilder {

    private String title   = "API";
    private String version = "1.0.0";
    private String description = "";

    private final Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
    private final Map<String, Map<String, Class<?>>> operationHandlers = new LinkedHashMap<>();
    private OpenApiSecurityRegistry securityRegistry;

    // ── Configuration ─────────────────────────────────────────────────────────

    public OpenApiBuilder title(String title)             { this.title = title; return this; }
    public OpenApiBuilder version(String version)         { this.version = version; return this; }
    public OpenApiBuilder description(String description) { this.description = description; return this; }

    void setSecurityRegistry(OpenApiSecurityRegistry registry) {
        this.securityRegistry = registry;
    }

    // ── Operation registration ────────────────────────────────────────────────

    /**
     * Adds an operation derived from the handler's {@link Route}, {@link ApiOperation},
     * {@link ApiResponse}, and {@link ApiParam} annotations.
     */
    public void addOperation(Route route, ApiOperation op, Class<?> handlerClass) {
        String path   = normalizePath(route.path());
        String method = route.method().name().toLowerCase();

        Map<String, Object> pathItem  = paths.computeIfAbsent(path, k -> new LinkedHashMap<>());
        Map<String, Object> operation = new LinkedHashMap<>();

        if (!op.operationId().isEmpty())  operation.put("operationId", op.operationId());
        if (!op.summary().isEmpty())      operation.put("summary", op.summary());
        if (!op.description().isEmpty())  operation.put("description", op.description());
        if (op.tags().length > 0)         operation.put("tags", Arrays.asList(op.tags()));
        if (op.deprecated())              operation.put("deprecated", true);

        buildParameters(operation, handlerClass, route);
        buildResponses(operation, handlerClass);

        pathItem.put(method, operation);
        operationHandlers.computeIfAbsent(path, k -> new LinkedHashMap<>()).put(method, handlerClass);
    }

    // ── Spec build ────────────────────────────────────────────────────────────

    /**
     * Returns the complete OpenAPI 3.0.3 spec as a plain map ready for JSON
     * serialization. Called on each request to {@code /openapi.json} so that
     * handlers registered after the extension is installed are included.
     */
    public Map<String, Object> build() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("version", version);
        if (!description.isEmpty()) info.put("description", description);

        List<OpenApiSecurityContributor> contributors = securityRegistry != null
                ? securityRegistry.contributors() : List.of();

        // Build paths with security injected per-operation (fresh copy each time so
        // repeated calls don't accumulate duplicate security entries)
        Map<String, Object> renderedPaths = new LinkedHashMap<>();
        for (var pathEntry : paths.entrySet()) {
            Map<String, Object> renderedPathItem = new LinkedHashMap<>();
            Map<String, Class<?>> handlers = operationHandlers.getOrDefault(pathEntry.getKey(), Map.of());
            for (var methodEntry : pathEntry.getValue().entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> original = (Map<String, Object>) methodEntry.getValue();
                Map<String, Object> op = new LinkedHashMap<>(original); // shallow copy
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

        if (!contributors.isEmpty()) {
            Map<String, Object> schemes = new LinkedHashMap<>();
            for (OpenApiSecurityContributor c : contributors) {
                schemes.put(c.schemeName(), c.schemeDefinition());
            }
            spec.put("components", Map.of("securitySchemes", schemes));
        }

        return spec;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void buildParameters(Map<String, Object> op, Class<?> cls, Route route) {
        List<Map<String, Object>> params = new ArrayList<>();

        // Path params from @Route path — add them automatically as required
        String path = route.path();
        int i = 0;
        while (i < path.length()) {
            int open = path.indexOf('{', i);
            if (open < 0) break;
            int close = path.indexOf('}', open);
            if (close < 0) break;
            String name = path.substring(open + 1, close);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", name);
            p.put("in", "path");
            p.put("required", true);
            p.put("schema", Map.of("type", "string"));
            params.add(p);
            i = close + 1;
        }

        // Explicit @ApiParam annotations
        ApiParam[] apiParams = cls.getAnnotationsByType(ApiParam.class);
        for (ApiParam ann : apiParams) {
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
        ApiResponse[] annotations = cls.getAnnotationsByType(ApiResponse.class);
        Map<String, Object> responses = new LinkedHashMap<>();

        if (annotations.length == 0) {
            responses.put("200", Map.of("description", "OK"));
        } else {
            for (ApiResponse ann : annotations) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("description", ann.description().isEmpty() ? httpPhrase(ann.status()) : ann.description());
                if (ann.schema() != Void.class) {
                    r.put("content", Map.of(
                            "application/json", Map.of(
                                    "schema", Map.of("$ref", "#/components/schemas/" + ann.schema().getSimpleName()))));
                }
                responses.put(String.valueOf(ann.status()), r);
            }
        }
        op.put("responses", responses);
    }

    /** Converts Flash path params ({id}) to OpenAPI path params ({id}) — already compatible. */
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
            default  -> "";
        };
    }

    private static List<Map<String, List<String>>> buildOperationSecurity(
            List<OpenApiSecurityContributor> contributors, Class<?> handlerClass) {
        List<Map<String, List<String>>> security = new ArrayList<>();
        for (OpenApiSecurityContributor c : contributors) {
            List<String> scopes = c.requiredFor(handlerClass);
            if (scopes != null) {
                security.add(Map.of(c.schemeName(), scopes));
            }
        }
        return security;
    }
}
