package dev.relism.flash.ext.jackson.xml;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Http1HeaderMap;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestLine;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathViews;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The same handler shape as JSON, over the same types and the same constraints. */
class XmlHandlerTest {

    public record Order(@NotBlank String reference) {}

    static final class Place extends XmlHandler<Order> {
        @Override protected Object handle(Request request, Response response, Order body) {
            return body.reference();
        }
    }

    @Test
    void theBodyArrivesParsedAsTheTypeTheHandlerDeclares() throws Exception {
        Place handler = new Place();
        handler.bind(context());

        Object answer = handler.handle(request("<Order><reference>A-1</reference></Order>"), response());

        assertEquals("A-1", answer);
    }

    @Test
    void aBodyThatBreaksAConstraintNeverReachesTheHandler() throws Exception {
        Place handler = new Place();
        handler.bind(context());

        HttpException refused = assertThrows(HttpException.class,
                () -> handler.handle(request("<Order><reference></reference></Order>"), response()));

        assertEquals(422, refused.status());
    }

    @Test
    void aMalformedBodyIsABadRequest() throws Exception {
        Place handler = new Place();
        handler.bind(context());

        HttpException refused = assertThrows(HttpException.class,
                () -> handler.handle(request("<Order><reference>"), response()));

        assertEquals(400, refused.status());
    }

    private static FlashContext context() {
        FlashContext ctx = new FlashContext();
        ctx.provide(Xml.class, new Xml(XmlMapper.builder().build()));
        ctx.complete();
        return ctx;
    }

    private static Response response() {
        return new Response(200, ContentType.XML);
    }

    private static Request request(String body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/orders"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
