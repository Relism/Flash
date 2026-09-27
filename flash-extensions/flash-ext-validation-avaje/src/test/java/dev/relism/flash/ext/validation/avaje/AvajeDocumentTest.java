package dev.relism.flash.ext.validation.avaje;

import dev.relism.flash.App;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.ext.jackson.json.JsonHandler;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.POST;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** This engine documents what it enforces: the jakarta constraints and the ones that are its own. */
class AvajeDocumentTest {

    @POST("/registrations")
    public static final class Register extends JsonHandler<Registration, Registration> {
        @Override protected Registration handle(Request req, Response res, Registration body) { return body; }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new JsonModule(), new AvajeValidation(), new OpenApiModule("/openapi", "Registrations", "1.0.0"))
            .scan("dev.relism.flash.ext.validation.avaje"));

    @Test
    void jakartaRulesAppearInTheSchema() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("\"minLength\":1")
                .expectBodyContains("\"required\":[\"name\"]");
    }

    @Test
    void andSoDoTheOnesThisEngineAddsOfItsOwn() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("\"maxLength\":20")
                .expectBodyContains("\"format\":\"uri\"")
                .expectBodyContains("\"format\":\"uuid\"")
                .expectBodyContains("\"exclusiveMinimum\":true");
    }
}
