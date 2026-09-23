package dev.relism.flash.ext.jackson.json;

import dev.relism.flash.testing.FlashTest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The whole path: JSON in, constraints checked, status out — with no error handling wired up. */
class ValidatedBodyTest {

    record CreateUser(@NotBlank @Size(max = 8) String name, @Email String email, @Min(18) int age) {}

    @RegisterExtension
    static FlashTest app = FlashTest.of(configured -> {
        configured.install(new JsonExtension());

        configured.ctx().onReady(() -> {
            Json json = configured.ctx().require(Json.class);
            configured.post("/users", (req, res) ->
                    res.status(201).body("created:" + json.body(req, CreateUser.class).name()));
        });
    });

    @Test
    void validBodyReachesTheHandler() {
        app.request().json("{\"name\":\"alice\",\"email\":\"a@b.com\",\"age\":30}").post("/users")
                .expectStatus(201)
                .expectBody("created:alice");
    }

    @Test
    void constraintViolationBecomes422WithEveryFailureListed() {
        app.request().json("{\"name\":\"\",\"email\":\"nope\",\"age\":5}").post("/users")
                .expectStatus(422)
                .expectHeader("Content-Type", "application/json")
                .expectBodyContains("name must not be blank")
                .expectBodyContains("email must be a well-formed email address")
                .expectBodyContains("age must be at least 18");
    }

    @Test
    void malformedJsonBecomes400NotAValidationFailure() {
        app.request().json("not json").post("/users")
                .expectStatus(400)
                .expectBodyContains("Invalid request body");
    }

    /** Regression guard: HttpException used to reach the catch-all and come back as 500. */
    @Test
    void statusCarriedByTheExceptionSurvivesToTheWire() {
        assertEquals(422, app.request().json("{\"name\":\"x\",\"email\":\"a@b.com\",\"age\":1}")
                .post("/users").status());
    }

    @Test
    void errorBodyIsValidJsonEvenWhenTheMessageContainsQuotes() {
        app.request().json("{\"name\":\"waaaaaaaaaay-too-long\",\"email\":\"a@b.com\",\"age\":30}").post("/users")
                .expectStatus(422)
                .expectBodyContains("\"status\":422")
                .expectBodyContains("size must be at most 8");
    }
}
