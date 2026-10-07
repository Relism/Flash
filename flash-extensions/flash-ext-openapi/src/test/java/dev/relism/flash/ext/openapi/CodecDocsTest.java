package dev.relism.flash.ext.openapi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.App;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.ext.serde.Encoder;
import dev.relism.flash.ext.serde.CodecRequestHandler;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import java.lang.reflect.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The document says what the codecs say. Nothing here declares a media type: the handler names two
 * codecs and the published document reports what each of them writes.
 */
class CodecDocsTest {

    record Order(String reference) {}
    record Receipt(String number) {}

    @ApiOperation(summary = "Takes XML, answers JSON")
    public static final class PlaceOrder extends CodecRequestHandler<Xmlish, Order, Jsonish, Receipt> {
        @Override protected Receipt handle(Request request, Response response, Order order) {
            return new Receipt(order.reference());
        }
    }

    static final class Xmlish implements Codec {
        @Override public Decoder decoder(Type type) { return (bytes, offset, length) -> new Order("A-1"); }
        @Override public Encoder encoder(Type type) { throw new UnsupportedOperationException(); }
        @Override public Object write(Response response, Object value) { throw new UnsupportedOperationException(); }
        @Override public ContentType contentType() { return ContentType.XML; }
    }

    static final class Jsonish implements Codec {
        @Override public Decoder decoder(Type type) { throw new UnsupportedOperationException(); }
        @Override public Encoder encoder(Type type) {
            return new Encoder() {
                @Override public void encode(Object value, ByteWriter out) {
                    out.writeAscii("{\"number\":\"" + ((Receipt) value).number() + "\"}");
                }
                @Override public ContentType type() { return ContentType.JSON; }
            };
        }
        @Override public Object write(Response response, Object value) throws Exception {
            ByteWriter out = new ByteWriter(64);
            encoder(value.getClass()).encode(value, out);
            response.type(ContentType.JSON);
            return java.util.Arrays.copyOf(out.array(), out.length());
        }
        @Override public ContentType contentType() { return ContentType.JSON; }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .add(Xmlish.class, new Xmlish()).add(Jsonish.class, new Jsonish())
            .install(new OpenApiModule("/openapi", "Orders", "1.0.0"))
            .route(HttpMethod.POST, "/orders", PlaceOrder.class));

    @Test
    void eachEndIsDocumentedAsItsOwnCodecWrites() {
        String document = app.get("/openapi.json").expectStatus(200).body();

        String requestBody = document.substring(document.indexOf("\"requestBody\""));
        assertTrue(requestBody.startsWith("\"requestBody\":{\"required\":true,\"content\":{\"application/xml\""), requestBody);
        assertTrue(document.contains("\"200\":{\"description\":\"OK\",\"content\":{\"application/json\""), document);
    }
}
