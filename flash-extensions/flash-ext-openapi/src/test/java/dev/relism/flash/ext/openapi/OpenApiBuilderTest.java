package dev.relism.flash.ext.openapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.GET;
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
    @APIResponse(responseCode = "200", description = "User found", content = @Content(schema = UserDto.class))
    static class GetUserHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/users")
    @ApiOperation(summary = "List users")
    @APIResponse(responseCode = "200", content = @Content(schema = UserDto.class, array = true))
    static class ListUsersHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/ping")
    @ApiOperation(summary = "Ping")
    @APIResponse(responseCode = "204", description = "No content")
    static class PingHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/secure")
    @ApiOperation(summary = "Secure")
    @APIResponse(responseCode = "403", description = "Custom forbidden")
    static class SecureHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/typed")
    @ApiOperation(summary = "Typed")
    @APIResponse(responseCode = "200", content = @Content)
    static class TypedHandler extends RequestHandler {
        @Override
        public UserDto handle(Request request, Response response) {
            return new UserDto();
        }
    }

    @GET("/typed-list")
    @ApiOperation(summary = "Typed list")
    @APIResponse(responseCode = "200", content = @Content)
    static class TypedListHandler extends RequestHandler {
        @Override
        public List<UserDto> handle(Request request, Response response) {
            return List.of();
        }
    }

    @GET("/typed-map")
    @ApiOperation(summary = "Typed map")
    @APIResponse(responseCode = "200", content = @Content)
    static class TypedMapHandler extends RequestHandler {
        @Override
        public Map<String, UserDto> handle(Request request, Response response) {
            return Map.of();
        }
    }

    @GET("/merge")
    @ApiOperation(summary = "Merge")
    @APIResponse(responseCode = "200", description = "Manual 200")
    static class MergeHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/plain")
    @ApiOperation(summary = "Plain")
    static class PlainHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
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

    @dev.relism.flash.routing.POST("/bodies")
    @ApiOperation(summary = "Typed body")
    static final class TypedBodyHandler extends JsonLikeHandler<UserDto> {
        @Override protected UserDto handle(Request request, Response response, UserDto body) { return body; }
    }

    @dev.relism.flash.routing.Consumes(ContentType.JSON)
    static abstract class JsonLikeHandler<B> extends dev.relism.flash.models.BodyHandler<B> {
        @Override protected B body(Request request) { return null; }
    }

    @dev.relism.flash.routing.PUT("/declared-body")
    @ApiOperation(summary = "Declared body")
    @RequestBody(value = UserDto.class, array = true, required = false, description = "Users to store")
    static class DeclaredBodyHandler extends RequestHandler {
        @Override public Object handle(Request request, Response response) { return null; }
    }

    @GET("/bare")
    static class BareHandler extends RequestHandler {
        @Override public UserDto handle(Request request, Response response) { return null; }
    }

    @GET("/example")
    @ApiOperation(summary = "Example")
    @APIResponse(responseCode = "200", content = @Content(schema = UserDto.class, example = "{\"id\":\"usr-1\"}"))
    static class ExampleHandler extends RequestHandler {
        @Override public Object handle(Request request, Response response) { return null; }
    }

    @GET("/secure-too")
    @ApiOperation(summary = "Secure too")
    static class SecondSecureHandler extends RequestHandler {
        @Override public Object handle(Request request, Response response) { return null; }
    }

    @GET("/xml")
    @dev.relism.flash.routing.Produces(ContentType.XML)
    @ApiOperation(summary = "Answers XML")
    static class XmlAnswerHandler extends RequestHandler {
        @Override public UserDto handle(Request request, Response response) { return null; }
    }

    @Test
    void the_media_type_of_an_answer_is_the_handler_s_own() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(XmlAnswerHandler.class), XmlAnswerHandler.class.getAnnotation(ApiOperation.class), XmlAnswerHandler.class);

        Map<String, Object> get = getOperation(b.build(), "/xml", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> ok = cast(responses.get("200"));
        Map<String, Object> content = cast(ok.get("content"));

        assertTrue(content.containsKey("application/xml"), content.keySet().toString());
    }

    @Test
    void a_typed_handler_documents_its_body_without_saying_the_type_twice() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(TypedBodyHandler.class), TypedBodyHandler.class.getAnnotation(ApiOperation.class), TypedBodyHandler.class);

        Map<String, Object> post = getOperation(b.build(), "/bodies", "post");
        Map<String, Object> body = cast(post.get("requestBody"));
        Map<String, Object> content = cast(body.get("content"));
        Map<String, Object> json = cast(content.get("application/json"));
        Map<String, Object> schema = cast(json.get("schema"));

        assertEquals(true, body.get("required"));
        assertEquals("#/components/schemas/UserDTO", schema.get("$ref"));
    }

    @Test
    void a_declared_body_wins_and_carries_its_own_shape() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(DeclaredBodyHandler.class), DeclaredBodyHandler.class.getAnnotation(ApiOperation.class), DeclaredBodyHandler.class);

        Map<String, Object> put = getOperation(b.build(), "/declared-body", "put");
        Map<String, Object> body = cast(put.get("requestBody"));
        Map<String, Object> content = cast(body.get("content"));
        Map<String, Object> json = cast(content.get("application/json"));
        Map<String, Object> schema = cast(json.get("schema"));

        assertEquals("Users to store", body.get("description"));
        assertEquals(false, body.get("required"));
        assertEquals("array", schema.get("type"));
    }

    @Test
    void a_route_with_no_annotations_is_still_documented() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(BareHandler.class), null, BareHandler.class);

        Map<String, Object> get = getOperation(b.build(), "/bare", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> ok = cast(responses.get("200"));
        Map<String, Object> content = cast(ok.get("content"));
        Map<String, Object> json = cast(content.get("application/json"));
        Map<String, Object> schema = cast(json.get("schema"));

        assertEquals("#/components/schemas/UserDTO", schema.get("$ref"));
    }

    @Test
    void a_failure_is_documented_with_the_shape_flash_answers_with() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(SecureHandler.class), SecureHandler.class.getAnnotation(ApiOperation.class), SecureHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/secure", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> forbidden = cast(responses.get("403"));
        Map<String, Object> content = cast(forbidden.get("content"));
        Map<String, Object> json = cast(content.get("application/json"));
        Map<String, Object> schema = cast(json.get("schema"));
        Map<String, Object> components = cast(spec.get("components"));
        Map<String, Object> schemas = cast(components.get("schemas"));

        assertEquals("#/components/schemas/Error", schema.get("$ref"));
        assertTrue(schemas.containsKey("Error"));
    }

    @Test
    void an_answer_two_operations_share_is_written_once() {
        OpenApiBuilder b = new OpenApiBuilder();
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        registry.add(new OpenApiContributor() {
            @Override public OpenApiOperationContribution operationFor(Class<?> handlerClass) {
                return OpenApiOperationContribution.builder()
                        .response(401, OpenApiResponseContribution.of("Authentication required"))
                        .build();
            }
        });
        b.setContributorRegistry(registry);
        b.addOperation(OpenApiBuilder.routeOf(SecureHandler.class), SecureHandler.class.getAnnotation(ApiOperation.class), SecureHandler.class);
        b.addOperation(OpenApiBuilder.routeOf(SecondSecureHandler.class), SecondSecureHandler.class.getAnnotation(ApiOperation.class), SecondSecureHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> components = cast(spec.get("components"));
        Map<String, Object> shared = cast(components.get("responses"));
        Map<String, Object> firstResponses = cast(getOperation(spec, "/secure", "get").get("responses"));
        Map<String, Object> secondResponses = cast(getOperation(spec, "/secure-too", "get").get("responses"));
        Map<String, Object> first = cast(firstResponses.get("401"));
        Map<String, Object> second = cast(secondResponses.get("401"));
        Map<String, Object> unauthorized = cast(shared.get("Unauthorized"));

        assertEquals("#/components/responses/Unauthorized", first.get("$ref"));
        assertEquals("#/components/responses/Unauthorized", second.get("$ref"));
        assertEquals("Authentication required", unauthorized.get("description"));
    }

    @Test
    void an_example_sits_beside_the_schema() {
        OpenApiBuilder b = new OpenApiBuilder();
        b.addOperation(OpenApiBuilder.routeOf(ExampleHandler.class), ExampleHandler.class.getAnnotation(ApiOperation.class), ExampleHandler.class);

        Map<String, Object> get = getOperation(b.build(), "/example", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> ok = cast(responses.get("200"));
        Map<String, Object> content = cast(ok.get("content"));
        Map<String, Object> json = cast(content.get("application/json"));

        assertEquals("{\"id\":\"usr-1\"}", json.get("example"));
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
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        registry.add(new OpenApiContributor() {
            @Override
            public Map<String, Object> componentContributions() {
                return Map.of("securitySchemes", Map.of("oidc", Map.of("type", "oauth2")));
            }

            @Override
            public OpenApiOperationContribution operationFor(Class<?> handlerClass) {
                return OpenApiOperationContribution.builder()
                        .security("oidc", List.of())
                        .response(401, OpenApiResponseContribution.of("Authentication required"))
                        .response(403, OpenApiResponseContribution.of("Auto forbidden"))
                        .build();
            }
        });
        b.setContributorRegistry(registry);
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

    @Test
    void contributor_response_merge_appliesAllResponses_andManualDescriptionWins() {
        OpenApiBuilder b = new OpenApiBuilder();
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        registry.add(new OpenApiContributor() {
            @Override
            public OpenApiOperationContribution operationFor(Class<?> handlerClass) {
                return OpenApiOperationContribution.builder()
                        .allResponses(OpenApiResponseContribution.builder()
                                .header("X-Trace", Map.of("schema", Map.of("type", "string")))
                                .build())
                        .response(200, OpenApiResponseContribution.of("Auto 200"))
                        .response(429, OpenApiResponseContribution.of("Auto 429"))
                        .build();
            }
        });
        b.setContributorRegistry(registry);
        b.addOperation(OpenApiBuilder.routeOf(MergeHandler.class), MergeHandler.class.getAnnotation(ApiOperation.class), MergeHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/merge", "get");
        Map<String, Object> responses = cast(get.get("responses"));

        Map<String, Object> resp200 = cast(responses.get("200"));
        assertEquals("Manual 200", resp200.get("description"));
        Map<String, Object> headers200 = cast(resp200.get("headers"));
        assertTrue(headers200.containsKey("X-Trace"));

        Map<String, Object> resp429 = cast(responses.get("429"));
        assertEquals("Auto 429", resp429.get("description"));
        Map<String, Object> headers429 = cast(resp429.get("headers"));
        assertTrue(headers429.containsKey("X-Trace"));
    }

    @Test
    void contributor_lastWins_forResponseHeaderCollisions() {
        OpenApiBuilder b = new OpenApiBuilder();
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        registry.add(new OpenApiContributor() {
            @Override
            public OpenApiOperationContribution operationFor(Class<?> handlerClass) {
                return OpenApiOperationContribution.builder()
                        .response(200, OpenApiResponseContribution.builder()
                                .header("X-RateLimit-Limit", Map.of("description", "old", "schema", Map.of("type", "integer")))
                                .build())
                        .build();
            }
        });
        registry.add(new OpenApiContributor() {
            @Override
            public OpenApiOperationContribution operationFor(Class<?> handlerClass) {
                return OpenApiOperationContribution.builder()
                        .response(200, OpenApiResponseContribution.builder()
                                .header("X-RateLimit-Limit", Map.of("description", "new", "schema", Map.of("type", "integer")))
                                .build())
                        .build();
            }
        });

        b.setContributorRegistry(registry);
        b.addOperation(OpenApiBuilder.routeOf(MergeHandler.class), MergeHandler.class.getAnnotation(ApiOperation.class), MergeHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> get = getOperation(spec, "/merge", "get");
        Map<String, Object> responses = cast(get.get("responses"));
        Map<String, Object> resp200 = cast(responses.get("200"));
        Map<String, Object> headers = cast(resp200.get("headers"));
        Map<String, Object> header = cast(headers.get("X-RateLimit-Limit"));
        assertEquals("new", header.get("description"));
    }

    @Test
    void contributor_merges_into_the_default_response_of_an_operation_without_annotations() {
        OpenApiBuilder b = new OpenApiBuilder();
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        registry.add(new OpenApiContributor() {
            @Override
            public OpenApiOperationContribution operationFor(Class<?> handlerClass) {
                return OpenApiOperationContribution.builder()
                        .allResponses(OpenApiResponseContribution.builder()
                                .header("X-Trace", Map.of("schema", Map.of("type", "string")))
                                .build())
                        .response(401, OpenApiResponseContribution.of("Authentication required"))
                        .build();
            }
        });
        b.setContributorRegistry(registry);
        b.addOperation(OpenApiBuilder.routeOf(PlainHandler.class), PlainHandler.class.getAnnotation(ApiOperation.class), PlainHandler.class);

        Map<String, Object> spec = b.build();
        Map<String, Object> responses = cast(getOperation(spec, "/plain", "get").get("responses"));
        Map<String, Object> resp200 = cast(responses.get("200"));
        Map<String, Object> resp401 = cast(responses.get("401"));
        Map<String, Object> headers = cast(resp200.get("headers"));
        assertEquals("OK", resp200.get("description"));
        assertTrue(headers.containsKey("X-Trace"));
        assertEquals("Authentication required", resp401.get("description"));
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
