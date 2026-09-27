package dev.relism.flash.ext.validation;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.ext.openapi.SchemaProperty;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.GET;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An engine's vocabulary reaches the document without this module knowing a single rule by name:
 * the annotation below exists only in this test, and the schema carries what the engine said of it.
 */
class ValidationDocsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Rule {
        int max();
        String says() default "";
    }

    public record Account(@Rule(max = 40, says = "uses letters only") String name,
                          @SchemaProperty(description = "Already said.") @Rule(max = 8) String code) {}

    @GET("/accounts")
    public static final class Accounts implements Handler {
        @Override public Account handle(Request req, Response res) { return null; }
    }

    /** Verifies nothing, describes one rule: enough to prove the seam. */
    static final class Engine implements Module, Validation {
        @Override public void install(App app) {
            app.add(Validation.class, this).install(Validation.docs());
        }
        @Override public void verify(Object value) {}
        @Override public void describe(Constraints constraints) {
            constraints.of(Rule.class, (rule, facts) -> {
                facts.required();
                facts.put("maxLength", rule.max());
                facts.describe(rule.says());
            });
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new Engine(), new OpenApiModule("/openapi", "Accounts", "1.0.0"))
            .scan("dev.relism.flash.ext.validation"));

    @Test
    void whatTheEngineSaysOfItsRuleIsInTheSchema() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("\"maxLength\":40")
                .expectBodyContains("\"maxLength\":8")
                .expectBodyContains("\"required\":[\"name\",\"code\"]");
    }

    @Test
    void aRuleSpeaksInWordsOnlyWhereTheCodeSaidNothing() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("\"description\":\"Uses letters only.\"")
                .expectBodyContains("\"description\":\"Already said.\"");
    }
}
