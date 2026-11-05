package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.http.HttpException;
import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.ext.validation.ValidationException;
import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import dev.relism.flash.http.Response;
import dev.relism.flash.bytes.FastPathViews;
import io.avaje.jsonb.Jsonb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    private final Json json = new Json(Jsonb.builder().build());

    record Unmapped(String text) {}

    @Test
    void aFieldIsNamedAsTheGeneratedAdapterWritesIt() throws Exception {
        assertEquals("user_name", json.wireName(Account.class.getDeclaredField("name")));
        assertEquals("id", json.wireName(Order.class.getDeclaredField("id")));
        assertEquals("text", json.wireName(Unmapped.class.getDeclaredField("text")), "no adapter: the Java name");
        assertEquals("enabled", json.wireName(Flag.class.getDeclaredField("active")), "read through isActive()");
    }

    @Test
    void aBodyIsReadAsTheTypeAsked() throws Exception {
        assertEquals(new Order("o1", 3), json.decoder(Order.class).decode(request("{\"id\":\"o1\",\"quantity\":3}")));
    }

    @Test
    void aMalformedBodyIsABadRequest() {
        HttpException refused = assertThrows(HttpException.class, () -> json.decoder(Order.class).decode(request("not json")));

        assertEquals(400, refused.status());
        assertTrue(refused.getMessage().startsWith("Invalid request body:"), refused.getMessage());
    }

    @Test
    void aTruncatedOrEmptyBodyIsABadRequestToo() {
        assertEquals(400, assertThrows(HttpException.class, () -> json.decoder(Order.class).decode(request("{\"id\":"))).status());
        assertEquals(400, assertThrows(HttpException.class, () -> json.decoder(Order.class).decode(request(""))).status());
    }

    @Test
    void writeSetsTheContentTypeAndAnswersBytes() {
        Response response = new Response(200, ContentType.TEXT_PLAIN);

        byte[] body = json.write(response, new Order("o2", 1));

        assertEquals("application/json", new String(response.getContentType(), StandardCharsets.UTF_8));
        assertArrayEquals("{\"id\":\"o2\",\"quantity\":1}".getBytes(StandardCharsets.UTF_8), body);
    }

    @Test
    void aTypeTheGeneratorNeverSawFailsWhenAHandlerAsksAtBoot() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> json.decoder(Unmapped.class));

        assertTrue(refused.getMessage().contains("No JsonAdapter"), refused.getMessage());
    }

    @Test
    void anInterfaceIsLeftToItsRuntimeType() {
        assertDoesNotThrow(() -> json.encoder(Runnable.class));
    }

    @RegisterExtension
    static FlashTest checked = FlashTest.of(() -> App.create().install(new JsonModule(), Validation.module(value -> {
        throw new ValidationException(List.of(new ValidationException.Violation("quantity", "must be positive")));
    })));

    @Test
    void theInstalledEngineChecksWhatIsRead() {
        ValidationException refused = assertThrows(ValidationException.class,
                () -> Decoder.of(checked.services().get(Json.class), Order.class, checked.services())
                        .decode(request("{\"id\":\"o3\",\"quantity\":0}")));

        assertEquals(422, refused.status());
    }

    static Request request(String body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/orders"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
