package dev.relism.flash.ext.validation.jakarta;

import dev.relism.flash.App;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.ext.jackson.json.JsonHandler;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.POST;
import dev.relism.flash.testing.FlashTest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A rule is written once and read twice: the engine enforces it, and the same engine says what it
 * means so the document carries it. Nothing here registers that bridge.
 */
class JakartaDocumentTest {

    public record Account(
            @NotBlank @Size(max = 40) String name,
            @Email String email,
            @Min(18) @Max(120) int age,
            @Pattern(regexp = "[a-z.]+", message = "uses lowercase letters and dots") String key) {}

    @POST("/accounts")
    public static final class Create extends JsonHandler<Account, Account> {
        @Override protected Account handle(Request req, Response res, Account body) { return body; }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new JsonModule(), new JakartaValidation(), new OpenApiModule("/openapi", "Accounts", "1.0.0"))
            .scan("dev.relism.flash.ext.validation.jakarta"));

    @Test
    void theRulesAppearInTheSchema() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("\"maxLength\":40")
                .expectBodyContains("\"minLength\":1")
                .expectBodyContains("\"format\":\"email\"")
                .expectBodyContains("\"minimum\":18")
                .expectBodyContains("\"maximum\":120")
                .expectBodyContains("\"pattern\":\"[a-z.]+\"");
    }

    @Test
    void whatCannotBeAKeywordIsSaidInWords() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("Uses lowercase letters and dots.")
                .expectBodyContains("\"required\":[\"name\"]");
    }
}
