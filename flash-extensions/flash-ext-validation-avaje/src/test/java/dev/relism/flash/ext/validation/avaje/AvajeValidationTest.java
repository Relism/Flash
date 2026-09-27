package dev.relism.flash.ext.validation.avaje;

import dev.relism.flash.ext.jackson.json.Json;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.App;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The same rules as the jakarta engine, checked by generated adapters: the types carry Avaje's
 * {@code @Valid} so its processor writes one, and the constraints themselves stay jakarta's.
 */
class AvajeValidationTest {

    /** Reads the body through the codec, which has the engine check it. */
    public static final class Create implements Handler {
        private final Json json;

        public Create(Json json) {
            this.json = json;
        }

        @Override
        public Object handle(Request req, Response res) throws Exception {
            return res.status(201).body("created:" + json.read(req, CreateUser.class).name());
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new JsonModule(), new AvajeValidation())
            .route(HttpMethod.POST, "/users", Create.class));

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
