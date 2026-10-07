package dev.relism.flash.ext.validation.jakarta;

import dev.relism.flash.ext.jackson.json.JsonHandler;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.App;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The whole path: JSON in, the engine's rules checked, status out — with no error handling wired up. */
class JakartaValidationTest {

    record Address(@NotBlank String street) {}
    record CreateUser(@NotBlank @Size(max = 8) String name, @Email String email, @Min(18) int age,
                      @Valid Address address) {}

    /** Reads the body through the codec, which has the engine check it. */
    public static final class Create extends JsonHandler<CreateUser, String> {
        @Override
        protected String handle(Request req, Response res, CreateUser user) {
            res.status(201);
            return "created:" + user.name();
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new JsonModule(), new JakartaValidation())
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

    /** What our own engine could never do: the rules of a nested object, through {@code @Valid}. */
    @Test
    void aNestedObjectIsCheckedThroughValid() {
        app.request().json("{\"name\":\"alice\",\"email\":\"a@b.com\",\"age\":30,\"address\":{\"street\":\"\"}}")
                .post("/users")
                .expectStatus(422)
                .expectBodyContains("address.street");
    }

    @Test
    void malformedJsonIsA400AndNotAValidationFailure() {
        app.request().json("not json").post("/users")
                .expectStatus(400)
                .expectBodyContains("Invalid request body");
    }
}
