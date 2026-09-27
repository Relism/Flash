package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import dev.relism.flash.http.Response;
import dev.relism.flash.bytes.FastPathViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonHandlerTest {

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create().add(Json.class, new Json(new ObjectMapper())));

    public record NewUser(String name) {}

    static final class Create extends JsonHandler<NewUser, String> {
        @Override protected String handle(Request request, Response response, NewUser body) {
            return body.name();
        }
    }

    static final class Rename extends JsonHandler<java.util.List<NewUser>, Integer> {
        @Override protected Integer handle(Request request, Response response, java.util.List<NewUser> body) {
            return body.size();
        }
    }

    @SuppressWarnings("rawtypes")
    static final class Untyped extends JsonHandler {
        @Override protected Object handle(Request request, Response response, Object body) {
            return null;
        }
    }

    @Test
    void theBodyArrivesParsedAsTheTypeTheHandlerDeclares() throws Exception {
        Create handler = app.services().create(Create.class);

        Object answer = handler.handle(request("{\"name\":\"alice\"}"), new Response(200, ContentType.JSON));

        assertEquals("alice", answer, "text is already a body, so the codec leaves it alone");
    }

    @Test
    void aGenericBodyIsParsedAsWhatItSays() throws Exception {
        Rename handler = app.services().create(Rename.class);

        Object answer = handler.handle(request("[{\"name\":\"alice\"},{\"name\":\"bob\"}]"),
                new Response(200, ContentType.JSON));

        assertEquals("2", new String((byte[]) answer, StandardCharsets.UTF_8), "two users, counted after Jackson built the list");
    }

    @Test
    void aMalformedBodyIsTheUsualBadRequest() throws Exception {
        Create handler = app.services().create(Create.class);

        dev.relism.flash.http.HttpException refused = assertThrows(dev.relism.flash.http.HttpException.class,
                () -> handler.handle(request("not json"), new Response(200, ContentType.JSON)));

        assertEquals(400, refused.status());
    }

    @Test
    void aHandlerThatNeverNamedItsTypesIsRefusedAtBoot() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> app.services().create(Untyped.class));

        assertTrue(refused.getMessage().contains("YourRequest, YourAnswer"), refused.getMessage());
    }


    private static Request request(String body) {
        return new Request(new RequestLine(HttpMethod.POST,
                new FastPathViews.StringByteView("/users"), null,
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
