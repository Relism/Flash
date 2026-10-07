package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import io.avaje.jsonb.Jsonb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static dev.relism.flash.ext.avaje.jsonb.JsonTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonHandlerTest {

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create().add(Json.class, new Json(Jsonb.builder().build())));

    record Unmapped(String text) {}

    static final class Place extends JsonHandler<Order, Order> {
        @Override protected Order handle(Request request, Response response, Order order) {
            return new Order(order.id(), order.quantity() + 1);
        }
        Object invoke(Request request, Response response) throws Exception { return super.handle(request, response); }
    }

    static final class Count extends JsonHandler<List<Order>, Integer> {
        @Override protected Integer handle(Request request, Response response, List<Order> orders) {
            return orders.size();
        }
        Object invoke(Request request, Response response) throws Exception { return super.handle(request, response); }
    }

    static final class Blind extends JsonHandler<Unmapped, String> {
        @Override protected String handle(Request request, Response response, Unmapped body) {
            return body.text();
        }
    }

    @Test
    void theBodyArrivesParsedAndTheAnswerLeavesAsJson() throws Exception {
        Place handler = app.services().create(Place.class);

        Object body = handler.invoke(request("{\"id\":\"o1\",\"quantity\":1}"), new Response(200, ContentType.NONE));

        assertEquals("{\"id\":\"o1\",\"quantity\":2}", new String((byte[]) body, StandardCharsets.UTF_8));
    }

    @Test
    void aGenericBodyIsParsedAsWhatItSays() throws Exception {
        Count handler = app.services().create(Count.class);

        Object body = handler.invoke(request("[{\"id\":\"a\",\"quantity\":1},{\"id\":\"b\",\"quantity\":2}]"),
                new Response(200, ContentType.NONE));

        assertEquals("2", new String((byte[]) body, StandardCharsets.UTF_8));
    }

    @Test
    void aTypeWithoutAnAdapterFailsTheBoot() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> app.services().create(Blind.class));

        assertTrue(refused.getMessage().contains(Unmapped.class.getName()), refused.getMessage());
    }

}
