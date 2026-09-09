package dev.relism.flash.ext.validation;

import dev.relism.flash.ext.jackson.JacksonExtension;
import dev.relism.flash.ext.openapi.APIResponse;
import dev.relism.flash.ext.openapi.ApiOperation;
import dev.relism.flash.ext.openapi.Content;
import dev.relism.flash.ext.openapi.OpenApiExtension;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.GET;
import dev.relism.flash.testing.FlashTest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Constraints are declared once and read twice: the validator enforces them, the published schema
 * describes them. Nothing registers this bridge — flash-ext-openapi picks the annotations up on
 * its own when they are on the classpath.
 */
class ValidationOpenApiInteropTest {

    record Account(
            @NotBlank @Size(max = 40) String name,
            @Email                    String email,
            @Min(18) @Max(120)        int    age) {}

    @GET("/accounts")
    @ApiOperation(summary = "List accounts")
    @APIResponse(responseCode = "200", content = @Content(contentType = ContentType.JSON, schema = Account.class))
    public static class ListAccounts extends RequestHandler {
        @Override public Object handle(Request request, Response response) {
            return new Account("alice", "a@b.com", 30);
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(configured -> {
        configured.install(new JacksonExtension());
        configured.install(new ValidationExtension());
        configured.install(new OpenApiExtension("/openapi", "Accounts", "1.0.0"));
        configured.scan("dev.relism.flash.ext.validation");
    });

    @Test
    void constraintsAppearInTheGeneratedSchema() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("\"maxLength\":40")
                .expectBodyContains("\"format\":\"email\"")
                .expectBodyContains("\"minimum\":18")
                .expectBodyContains("\"maximum\":120");
    }

    @Test
    void notBlankMarksThePropertyRequiredAndNonEmpty() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("\"minLength\":1")
                .expectBodyContains("\"required\":[\"name\"]");
    }
}
