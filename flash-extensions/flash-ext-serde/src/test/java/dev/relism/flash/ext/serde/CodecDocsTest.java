package dev.relism.flash.ext.serde;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.App;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.openapi.ApiOperation;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The document says what the codecs say. Nothing here declares a media type: the handler names two
 * codecs and the published document reports what each of them writes.
 */
class CodecDocsTest {

    record Order(String reference, String internal, String secret) {}
    record Receipt(String number) {}

    @ApiOperation(summary = "Takes XML, answers JSON")
    public static final class PlaceOrder extends CodecRequestHandler<Xmlish, Order, Jsonish, Receipt> {
        @Override protected Receipt handle(Request request, Response response, Order order) {
            return new Receipt(order.reference());
        }
    }

    static final class Xmlish implements Codec {
        @Override public Decoder decoder(Type type) { return (bytes, offset, length) -> new Order("A-1", null, null); }
        @Override public Encoder encoder(Type type) { throw new UnsupportedOperationException(); }
        @Override public Object write(Response response, Object value) { throw new UnsupportedOperationException(); }
        @Override public ContentType contentType() { return ContentType.XML; }
        @Override public String wireName(Field field) { return field.getName().equals("secret") ? null : field.getName(); }
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
        /** Writes {@code Receipt.number} as {@code receipt_no}, and neither {@code Order.internal} nor {@code Order.secret}. */
        @Override public String wireName(Field field) {
            if (field.getName().equals("internal") || field.getName().equals("secret")) return null;
            return field.getName().equals("number") ? "receipt_no" : field.getName();
        }
    }

    @FromRequest
    record Lookup(String reference) {}

    public static final class FindOrder extends CodecRequestHandler<Xmlish, Lookup, Jsonish, Receipt> {
        @Override protected Receipt handle(Request request, Response response, Lookup lookup) {
            return new Receipt(lookup.reference());
        }
    }

    /** Reads a {@link Lookup} from the query. */
    static final class FromQuery implements InputBinder {
        @Override public Reader reader(Type type, Codec codec) {
            return type == Lookup.class ? request -> new Lookup(request.query("reference")) : null;
        }
    }

    /** Says what a {@link Lookup} is read from, as the module that reads it would. */
    static final class LookupDocs implements OpenApiContributor {
        @Override public OpenApiOperationContribution parameters(Type type, Function<Field, String> names) {
            if (type != Lookup.class) return null;
            return OpenApiOperationContribution.builder()
                    .parameter(Map.of("name", "reference", "in", "query", "schema", Map.of("type", "string")))
                    .build();
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(Codec.module(Xmlish.class, new Xmlish()), Codec.module(Jsonish.class, new Jsonish()))
            .add(InputBinder.class, FromQuery.class)
            .add(OpenApiContributor.class, LookupDocs.class)
            .install(new OpenApiModule("/openapi", "Orders", "1.0.0"))
            .route(HttpMethod.POST, "/orders", PlaceOrder.class)
            .route(HttpMethod.GET, "/orders", FindOrder.class));

    @Test
    void an_input_read_from_the_request_is_not_a_body() {
        String document = app.get("/openapi.json").expectStatus(200).body();
        String orders = document.substring(document.indexOf("\"/orders\""));
        String find = orders.substring(orders.indexOf("\"get\""));
        find = find.substring(0, find.indexOf("\"responses\""));
        assertTrue(orders.substring(0, orders.indexOf("\"get\"")).contains("requestBody"), "the POST still has its body");
        assertFalse(find.contains("requestBody"), find);
        assertTrue(find.contains("\"name\":\"reference\""), "its parameters come from whoever describes the type: " + find);
        app.get("/orders?reference=A-7").expectStatus(200).expectBodyContains("A-7");
    }

    @Test
    void eachEndIsDocumentedAsItsOwnCodecWrites() {
        String document = app.get("/openapi.json").expectStatus(200).body();

        String requestBody = document.substring(document.indexOf("\"requestBody\""));
        assertTrue(requestBody.startsWith("\"requestBody\":{\"required\":true,\"content\":{\"application/xml\""), requestBody);
        assertTrue(document.contains("\"200\":{\"description\":\"OK\",\"content\":{\"application/json\""), document);
    }

    @Test
    void aFieldIsNamedAsTheCodecWritesItAndLeftOutOnlyWhenNoCodecCarriesIt() {
        String document = app.get("/openapi.json").expectStatus(200).body();

        assertTrue(document.contains("\"receipt_no\""), document);
        assertTrue(document.contains("\"internal\""), "the XML codec carries it: " + document);
        assertFalse(document.contains("\"secret\""), document);
    }
}
