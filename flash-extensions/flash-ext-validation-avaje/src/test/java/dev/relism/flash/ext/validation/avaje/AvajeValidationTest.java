package dev.relism.flash.ext.validation.avaje;

import dev.relism.flash.ext.jackson.json.JsonHandler;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.App;
import dev.relism.flash.ext.validation.ValidationException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import io.avaje.validation.constraints.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same rules as the jakarta engine, checked by generated adapters: the types carry Avaje's
 * {@code @Valid} so its processor writes one, and the constraints themselves stay jakarta's.
 */
class AvajeValidationTest {

    /** Package-private, nested, two bounds on a primitive: each once broke the generated adapter. */
    @Valid
    record Bounded(@Min(1) @Max(10) int n) {}

    @Test
    void twoBoundsOnANestedPrimitiveAreChecked() {
        AvajeValidation engine = new AvajeValidation();
        engine.verify(new Bounded(5));
        ValidationException broken = assertThrows(ValidationException.class, () -> engine.verify(new Bounded(50)));
        assertEquals("n", broken.violations().get(0).path());
    }

    /** No constraint, so the generator writes no adapter for it, @Valid or not. */
    @Valid
    record Unconstrained(boolean source, String fallbackLocale) {}

    /** A constraint without @Valid: no adapter either, and that one is a mistake. */
    record Unmarked(@NotBlank String name) {}

    @Test
    void aTypeWithNoConstraintHasNothingToCheck() {
        AvajeValidation engine = new AvajeValidation();
        engine.verify(new Unconstrained(true, null));
        engine.verify(new Unconstrained(false, "en"));
    }

    @Test
    void aConstrainedTypeWithoutAnAdapterIsRefusedByName() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new AvajeValidation().verify(new Unmarked("alice")));
        assertTrue(refused.getMessage().contains(Unmarked.class.getName()), refused.getMessage());
    }

    /** Reads the body through the codec, which has the engine check it. */
    public static final class Create extends JsonHandler<CreateUser, String> {
        @Override
        protected String handle(Request req, Response res, CreateUser user) {
            res.status(201);
            return "created:" + user.name();
        }
    }

    public static final class CreateAll extends JsonHandler<java.util.List<CreateUser>, String> {
        @Override
        protected String handle(Request req, Response res, java.util.List<CreateUser> users) {
            return "created:" + users.size();
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new JsonModule(), new AvajeValidation())
            .route(HttpMethod.POST, "/users", Create.class)
            .route(HttpMethod.POST, "/users/bulk", CreateAll.class));

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

    @Test
    void aCollectionBodyIsCheckedElementByElement() {
        String valid = "{\"name\":\"alice\",\"email\":\"a@b.com\",\"age\":30,\"address\":{\"street\":\"Main\"}}";
        String broken = "{\"name\":\"\",\"email\":\"a@b.com\",\"age\":30,\"address\":{\"street\":\"Main\"}}";
        app.request().json("[" + valid + "," + valid + "]").post("/users/bulk").expectStatus(200).expectBody("created:2");
        app.request().json("[" + valid + "," + broken + "]").post("/users/bulk")
                .expectStatus(422)
                .expectBodyContains("[1].name");
    }
}
