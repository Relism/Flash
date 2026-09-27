package dev.relism.flash.ext.serde;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.ApiOperation;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.POST;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The document says what the codecs say. Nothing here declares a media type: the handler names two
 * codecs and the published document reports what each of them writes.
 */
class CodecDocsTest {

    record Order(String reference) {}
    record Receipt(String number) {}

    @POST("/orders")
    @ApiOperation(summary = "Takes XML, answers JSON")
    public static final class PlaceOrder extends CodecHandler<Fake.Xmlish, Order, Fake.Jsonish, Receipt> {
        @Override protected Receipt handle(Request request, Response response, Order order) {
            return new Receipt(order.reference());
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new Fake(), new OpenApiModule("/openapi", "Orders", "1.0.0"))
            .scan("dev.relism.flash.ext.serde"));

    @Test
    void eachEndIsDocumentedAsItsOwnCodecWrites() {
        String document = app.get("/openapi.json").expectStatus(200).body();

        String requestBody = document.substring(document.indexOf("\"requestBody\""));
        assertTrue(requestBody.startsWith("\"requestBody\":{\"required\":true,\"content\":{\"application/xml\""), requestBody);
        assertTrue(document.contains("\"200\":{\"description\":\"OK\",\"content\":{\"application/json\""), document);
    }

    /** Two codecs and nothing else, installed the way a format module installs its own. */
    static final class Fake implements Module {

        @Override public void install(App app) {
            app.add(Xmlish.class, new Xmlish()).add(Jsonish.class, new Jsonish()).install(CodecDocs.module());
        }

        static final class Xmlish implements Codec {
            @SuppressWarnings("unchecked")
            @Override public <T> T read(Request request, Type type) { return (T) new Order("A-1"); }
            @Override public Object write(Response response, Object value) { return value.toString(); }
            @Override public ContentType contentType() { return ContentType.XML; }
        }

        static final class Jsonish implements Codec {
            @Override public <T> T read(Request request, Type type) { throw new UnsupportedOperationException(); }
            @Override public Object write(Response response, Object value) {
                response.type(ContentType.JSON);
                return "{\"number\":\"" + ((Receipt) value).number() + "\"}";
            }
            @Override public ContentType contentType() { return ContentType.JSON; }
        }
    }
}
