package dev.relism.flash.ext.validation.protovalidate;

import build.buf.protovalidate.ValidationResult;
import build.buf.protovalidate.Validator;
import build.buf.validate.FieldPath;
import build.buf.validate.FieldPathElement;
import com.google.protobuf.StringValue;
import dev.relism.flash.App;
import dev.relism.flash.ext.jackson.json.JsonHandler;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.ext.protobuf.ProtobufHandler;
import dev.relism.flash.ext.protobuf.ProtobufModule;
import dev.relism.flash.ext.validation.jakarta.JakartaValidation;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.POST;
import dev.relism.flash.testing.FlashTest;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON and protobuf in one application: each codec asks for its own engine, so neither is a choice. */
class AlongsideJakartaTest {

    public record Account(@NotBlank String name) {}

    @POST("/accounts")
    public static final class Create extends JsonHandler<Account, Account> {
        @Override protected Account handle(Request request, Response response, Account body) { return body; }
    }

    @POST("/words")
    public static final class Word extends ProtobufHandler<StringValue, StringValue> {
        @Override protected StringValue handle(Request request, Response response, StringValue body) { return body; }
    }

    /** Rejects "bad", the way a rule in a descriptor would. */
    private static final Validator RULES = message -> {
        build.buf.validate.Violation detail = build.buf.validate.Violation.newBuilder()
                .setField(FieldPath.newBuilder().addElements(FieldPathElement.newBuilder().setFieldName("value")))
                .setMessage("must not be bad").build();
        boolean bad = message instanceof StringValue text && text.getValue().equals("bad");
        return new ValidationResult(bad ? List.of(new build.buf.protovalidate.Violation() {
            @Override public build.buf.validate.Violation toProto() { return detail; }
            @Override public FieldValue getFieldValue() { return null; }
            @Override public FieldValue getRuleValue() { return null; }
        }) : List.of());
    };

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new JsonModule(), new JakartaValidation(), new ProtobufModule(), new ProtovalidateValidation(RULES))
            .scan("dev.relism.flash.ext.validation.protovalidate"));

    @Test
    void aJsonBodyIsCheckedByJakarta() {
        app.request().json("{\"name\":\"\"}").post("/accounts").expectStatus(422);
        app.request().json("{\"name\":\"ada\"}").post("/accounts").expectStatus(200);
    }

    @Test
    void aProtobufBodyIsCheckedByProtovalidate() throws Exception {
        assertEquals(422, word("bad").statusCode());
        assertEquals(200, word("fine").statusCode());
    }

    private static HttpResponse<byte[]> word(String text) throws Exception {
        return app.client().send(HttpRequest.newBuilder(URI.create(app.baseUri() + "/words"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(StringValue.of(text).toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void protovalidateWithoutTheProtobufCodecFailsTheBoot() {
        FlashTest alone = FlashTest.of(() -> App.create().install(new ProtovalidateValidation(RULES)));

        StringBuilder said = new StringBuilder();
        for (Throwable cause = assertThrows(RuntimeException.class, alone::services); cause != null; cause = cause.getCause())
            said.append(cause.getMessage()).append(' ');
        assertTrue(said.toString().contains("no ProtobufModule is installed"), said.toString());
    }
}
