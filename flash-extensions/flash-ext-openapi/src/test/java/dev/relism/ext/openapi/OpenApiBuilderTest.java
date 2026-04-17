package dev.relism.ext.openapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.relism.http.ContentType;
import dev.relism.routing.GET;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiBuilderTest {

    @GET("/users/{id}")
    @ApiOperation(summary = "Get user")
    @Parameter(name = "expand", in = ParameterIn.QUERY, required = false, type = SchemaType.STRING, examples = {"roles", "permissions"})
    @APIResponse(responseCode = "200", description = "User found", content = @Content(contentType = ContentType.JSON, schema = UserDto.class))
    static class GetUserHandler extends dev.relism.models.RequestHandler {
        @Override
        public Object handle(dev.relism.models.Request request, dev.relism.models.Response response) {
            return null;
        }
    }

    @GET("/users")
    @ApiOperation(summary = "List users")
    @APIResponse(responseCode = "200", content = @Content(contentType = ContentType.JSON, schema = UserDto.class, array = true))
    static class ListUsersHandler extends dev.relism.models.RequestHandler {
        @Override
        public Object handle(dev.relism.models.Request request, dev.relism.models.Response response) {
            return null;
        }
    }

    @GET("/ping")
    @ApiOperation(summary = "Ping")
    @APIResponse(responseCode = "204", description = "No content", content = @Content(contentType = ContentType.NONE))
    static class PingHandler extends dev.relism.models.RequestHandler {
        @Override
        public Object handle(dev.relism.models.Request request, dev.relism.models.Response response) {
            return null;
        }
    }

    @GET("/secure")
    @ApiOperation(summary = "Secure")
    @APIResponse(responseCode = "403", description = "Custom forbidden")
    static class SecureHandler extends dev.relism.models.RequestHandler {
        @Override
        public Object handle(dev.relism.models.Request request, dev.relism.models.Response response) {
            return null;
        }
    }

    @GET("/typed")
    @ApiOperation(summary = "Typed")
    @APIResponse(responseCode = "200", content = @Content)
    static class TypedHandler extends dev.relism.models.RequestHandler {
        @Override
        public UserDto handle(dev.relism.models.Request request, dev.relism.models.Response response) {
            return new UserDto();
        }
    }

    @GET("/typed-list")
    @ApiOperation(summary = "Typed list")
    @APIResponse(responseCode = "200", content = @Content)
    static class TypedListHandler extends dev.relism.models.RequestHandler {
        @Override
        public List<UserDto> handle(dev.relism.models.Request request, dev.relism.models.Response response) {
            return List.of();
        }
    }

    @GET("/typed-map")
    @ApiOperation(summary = "Typed map")
    @APIResponse(responseCode = "200", content = @Content)
    static class TypedMapHandler extends dev.relism.models.RequestHandler {
        @Override
        public Map<String, UserDto> handle(dev.relism.models.Request request, dev.relism.models.Response response) {
            return Map.of();
        }
    }

    @Schema(name = "UserDTO", title = "User model", description = "DTO", deprecated = true)
    @JsonIgnoreProperties({"ignoredByType"})
    static class UserDto {
        @SchemaProperty(title = "Identifier", description = "Unique id", required = true, example = "usr-1", enumeration = {"usr-1", "usr-2"})
        public String id;

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String readOnlyField;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String writeOnlyField;

        @JsonIgnore
        public String hiddenByIgnore;

        public transient String transientField;

        public String ignoredByType;

        @ArraySchema(uniqueItems = true, minItems = 1)
        public List<String> tags;
    }

    @Test
    void builds_single_response_and_parameters_and_schema() {
        OpenApiBuilder b = new OpenApiBuilder().title("X").version("1");
        b.addOperation(OpenApiBuilder.routeOf(GetUserHandler.class), GetUserHandler.class.getAnnotation(ApiOperation.class), GetUserHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> paths = cast(spec.get("paths"));
        Map<String, Object> userPath = cast(paths.get("/users/{id}"));
        Map<String, Object> get = cast(userPath.get("get"));

        List<Map<String, Object>> params = cast(get.get("parameters"));
        assertEquals(2, params.size());

        Map<String, Object> responses = cast(get.get("responses"));
        assertTrue(responses.containsKey("200"));

        Map<String, Object> resp200 = cast(responses.get("200"));
        Map<String, Object> content = cast(resp200.get("content"));
        assertTrue(content.containsKey("application/json"));

        Map<String, Object> components = cast(spec.get("components"));
        Map<String, Object> schemas = cast(components.get("schemas"));
        assertTrue(schemas.containsKey("UserDTO"));
        Map<String, Object> userSchema = cast(schemas.get("UserDTO"));
        assertEquals("User model", userSchema.get("title"));
        assertEquals(true, userSchema.get("deprecated"));

        Map<String, Object> properties = cast(userSchema.get("properties"));
        assertFalse(properties.containsKey("hiddenByIgnore"));
        assertFalse(properties.containsKey("ignoredByType"));
        assertFalse(properties.containsKey("transientField"));
        assertTrue(properties.containsKey("id"));
        assertTrue(properties.containsKey("tags"));
    }

    @Test
    void builds_array_response() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(ListUsersHandler.class), ListUsersHandler.class.getAnnotation(ApiOperation.class), ListUsersHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/users", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> resp200 = cast(responses.get("200"));
        Map<String, Object> content = cast(resp200.get("content"));
        Map<String, Object> appJson = cast(content.get("application/json"));
        Map<String, Object> schema = cast(appJson.get("schema"));
        assertEquals("array", schema.get("type"));
    }

    @Test
    void builds_no_content_response_without_content_block() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(PingHandler.class), PingHandler.class.getAnnotation(ApiOperation.class), PingHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/ping", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> resp204 = cast(responses.get("204"));
        assertEquals("No content", resp204.get("description"));
        assertFalse(resp204.containsKey("content"));
    }

    @Test
    void auto_security_responses_are_added_and_manual_wins_sorted() {
        OpenApiBuilder b = new OpenApiBuilder();
        OpenApiSecurityRegistry registry = new OpenApiSecurityRegistry();
        registry.add(new OpenApiSecurityContributor() {
            @Override public String schemeName() { return "oidc"; }
            @Override public Map<String, Object> schemeDefinition() { return Map.of("type", "oauth2"); }
            @Override public List<String> requiredFor(Class<?> handlerClass) { return List.of(); }
            @Override public Map<Integer, String> autoResponsesFor(Class<?> handlerClass) {
                return Map.of(401, "Authentication required", 403, "Auto forbidden");
            }
        });
        b.setSecurityRegistry(registry);
        b.addOperation(OpenApiBuilder.routeOf(SecureHandler.class), SecureHandler.class.getAnnotation(ApiOperation.class), SecureHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/secure", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> resp403 = cast(responses.get("403"));
        Map<String, Object> resp401 = cast(responses.get("401"));
        assertEquals(List.of("401", "403"), new java.util.ArrayList<>(responses.keySet()));
        assertEquals("Custom forbidden", resp403.get("description"));
        assertEquals("Authentication required", resp401.get("description"));

        List<Map<String, List<String>>> security = cast(get.get("security"));
        assertNotNull(security);
        assertEquals(1, security.size());
    }

    @Test
    void use_return_type_for_response_schema() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(TypedHandler.class), TypedHandler.class.getAnnotation(ApiOperation.class), TypedHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/typed", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> resp200 = cast(responses.get("200"));
        Map<String, Object> content = cast(resp200.get("content"));
        assertTrue(content.containsKey("application/json"));
    }

    @Test
    void infer_array_schema_from_collection_return_type() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(TypedListHandler.class), TypedListHandler.class.getAnnotation(ApiOperation.class), TypedListHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/typed-list", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> resp200 = cast(responses.get("200"));
        Map<String, Object> content = cast(resp200.get("content"));
        Map<String, Object> appJson = cast(content.get("application/json"));
        Map<String, Object> schema = cast(appJson.get("schema"));
        assertEquals("array", schema.get("type"));
        Map<String, Object> items = cast(schema.get("items"));
        assertEquals("#/components/schemas/UserDTO", items.get("$ref"));
    }

    @Test
    void infer_map_value_schema_from_map_return_type() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(TypedMapHandler.class), TypedMapHandler.class.getAnnotation(ApiOperation.class), TypedMapHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/typed-map", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> resp200 = cast(responses.get("200"));
        Map<String, Object> content = cast(resp200.get("content"));
        Map<String, Object> appJson = cast(content.get("application/json"));
        Map<String, Object> schema = cast(appJson.get("schema"));
        assertEquals("object", schema.get("type"));
        Map<String, Object> additionalProperties = cast(schema.get("additionalProperties"));
        assertEquals("#/components/schemas/UserDTO", additionalProperties.get("$ref"));
    }

    private static Map<String, Object> getOperation(Map<String, Object> spec, String path, String method) {
        Map<String, Object> paths = cast(spec.get("paths"));
        Map<String, Object> pathItem = cast(paths.get(path));
        return cast(pathItem.get(method));
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
