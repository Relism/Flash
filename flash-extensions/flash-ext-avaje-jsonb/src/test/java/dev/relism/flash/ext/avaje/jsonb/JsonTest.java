package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.http.HttpException;
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
    void aBodyIsReadAsTheTypeAsked() throws Exception {
        assertEquals(new Order("o1", 3), json.read(request("{\"id\":\"o1\",\"quantity\":3}"), Order.class));
    }

    @Test
    void aMalformedBodyIsABadRequest() {
        HttpException refused = assertThrows(HttpException.class, () -> json.read(request("not json"), Order.class));

        assertEquals(400, refused.status());
        assertTrue(refused.getMessage().startsWith("Invalid request body:"), refused.getMessage());
    }

    @Test
    void aTruncatedOrEmptyBodyIsABadRequestToo() {
        assertEquals(400, assertThrows(HttpException.class, () -> json.read(request("{\"id\":"), Order.class)).status());
        assertEquals(400, assertThrows(HttpException.class, () -> json.read(request(""), Order.class)).status());
    }

    @Test
    void writeSetsTheContentTypeAndAnswersBytes() {
        Response response = new Response(200, ContentType.TEXT_PLAIN);

        byte[] body = json.write(response, new Order("o2", 1));

        assertEquals("application/json", new String(response.getContentType(), StandardCharsets.UTF_8));
        assertArrayEquals("{\"id\":\"o2\",\"quantity\":1}".getBytes(StandardCharsets.UTF_8), body);
    }

    @Test
    void aTypeTheGeneratorNeverSawFailsWhenPrepared() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> json.prepare(Unmapped.class));

        assertTrue(refused.getMessage().contains("No JsonAdapter"), refused.getMessage());
    }

    @Test
    void anInterfaceIsLeftToItsRuntimeType() {
        assertDoesNotThrow(() -> json.prepare(Runnable.class));
    }

    @RegisterExtension
    static FlashTest checked = FlashTest.of(() -> App.create().install(new JsonModule()).add(Validation.class, value -> {
        throw new ValidationException(List.of(new ValidationException.Violation("quantity", "must be positive")));
    }));

    @Test
    void theInstalledEngineChecksWhatIsRead() {
        ValidationException refused = assertThrows(ValidationException.class,
                () -> checked.services().get(Json.class).read(request("{\"id\":\"o3\",\"quantity\":0}"), Order.class));

        assertEquals(422, refused.status());
    }

    static Request request(String body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/orders"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
