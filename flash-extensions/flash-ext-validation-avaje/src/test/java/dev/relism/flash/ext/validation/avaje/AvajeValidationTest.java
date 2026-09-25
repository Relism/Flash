package dev.relism.flash.ext.validation.avaje;

import dev.relism.flash.ext.jackson.json.Json;
import dev.relism.flash.ext.jackson.json.JsonExtension;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The same rules as the jakarta engine, checked by generated adapters: the types carry Avaje's
 * {@code @Valid} so its processor writes one, and the constraints themselves stay jakarta's.
 */
class AvajeValidationTest {

    @RegisterExtension
    static FlashTest app = FlashTest.of(configured -> {
        configured.install(new JsonExtension());
        configured.install(new AvajeValidationExtension());

        configured.ctx().onReady(() -> {
            Json json = configured.ctx().require(Json.class);
            configured.post("/users", (req, res) ->
                    res.status(201).body("created:" + json.read(req, CreateUser.class).name()));
        });
    });

    @Test
    void aValidBodyReachesTheHandler() {
        app.request().json("{\"name\":\"alice\",\"email\":\"a@b.com\",\"age\":30,\"address\":{\"street\":\"Main\"}}")
                .post("/users")
                .expectStatus(201)
                .expectBody("created:alice");
    }

    @Test
    void everyBrokenRuleComesBackAtOnceAs422() {
        app.request().json("{\"name\":\"\",\"email\":\"nope\",\"age\":5,\"address\":{\"street\":\"Main\"}}")
                .post("/users")
                .expectStatus(422)
                .expectHeader("Content-Type", "application/json")
                .expectBodyContains("name")
                .expectBodyContains("email")
                .expectBodyContains("age");
    }

    @Test
    void aNestedObjectIsCheckedThroughValid() {
        app.request().json("{\"name\":\"alice\",\"email\":\"a@b.com\",\"age\":30,\"address\":{\"street\":\"\"}}")
                .post("/users")
                .expectStatus(422)
                .expectBodyContains("street");
    }

    @Test
    void malformedJsonIsA400AndNotAValidationFailure() {
        app.request().json("not json").post("/users")
                .expectStatus(400)
                .expectBodyContains("Invalid request body");
    }
}
