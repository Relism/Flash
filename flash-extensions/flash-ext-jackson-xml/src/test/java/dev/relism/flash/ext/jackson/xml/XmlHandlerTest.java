package dev.relism.flash.ext.jackson.xml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.ext.jackson.json.Json;
import dev.relism.flash.ext.serde.CodecHandler;
import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import dev.relism.flash.http.Response;
import dev.relism.flash.bytes.FastPathViews;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The same handler shape as JSON, over the same types. What checks them is a validation engine's. */
class XmlHandlerTest {

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create().add(Xml.class, new Xml(XmlMapper.builder().build())).add(Json.class, new Json(new ObjectMapper())));

    public record Order(@NotBlank String reference) {}
    public record Receipt(String number) {}

    static final class Place extends XmlHandler<Order, String> {
        @Override protected String handle(Request request, Response response, Order body) {
            return body.reference();
        }
    }

    /** The two ends are independent: this one reads XML and answers JSON. */
    static final class Ship extends CodecHandler<Xml, Order, Json, Receipt> {
        @Override protected Receipt handle(Request request, Response response, Order body) {
            return new Receipt(body.reference());
        }
    }

    /** Reads nothing and answers XML, which is how a route that only delivers is written. */
    static final class Catalog extends XmlHandler<Void, Receipt> {
        @Override protected Receipt handle(Request request, Response response, Void nothing) {
            return new Receipt("catalog");
        }
    }

    @Test
    void theBodyArrivesParsedAsTheTypeTheHandlerDeclares() throws Exception {
        Place handler = app.services().create(Place.class);

        Object answer = handler.handle(request("<Order><reference>A-1</reference></Order>"), response());

        assertEquals("A-1", answer);
    }

    @Test
    void aMalformedBodyIsABadRequest() throws Exception {
        Place handler = app.services().create(Place.class);

        HttpException refused = assertThrows(HttpException.class,
                () -> handler.handle(request("<Order><reference>"), response()));

        assertEquals(400, refused.status());
    }

    @Test
    void aHandlerAnswersInTheCodecItNamesForTheAnswer() throws Exception {
        Ship handler = app.services().create(Ship.class);
        Response response = response();

        Object body = handler.handle(request("<Order><reference>A-1</reference></Order>"), response);

        assertEquals("{\"number\":\"A-1\"}", new String((byte[]) body, StandardCharsets.UTF_8));
        assertEquals("application/json", new String(response.getContentType(), StandardCharsets.UTF_8));
    }

    @Test
    void aHandlerThatReadsNothingStillAnswersItsOwnFormat() throws Exception {
        Catalog handler = app.services().create(Catalog.class);
        Response response = new Response(200, ContentType.NONE);

        Object body = handler.handle(request(""), response);

        assertEquals("<Receipt><number>catalog</number></Receipt>", new String((byte[]) body, StandardCharsets.UTF_8));
        assertEquals("application/xml", new String(response.getContentType(), StandardCharsets.UTF_8));
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
