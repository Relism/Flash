package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.HeaderMap;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestLine;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathViews;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void body_parses_valid_json_and_maps_bad_payload_to_http_400() throws Exception {
        Json json = new Json(new ObjectMapper());

        Request ok = request("{\"id\":\"u1\",\"name\":\"alice\"}");
        UserDto dto = json.body(ok, UserDto.class);
        assertEquals("u1", dto.id);
        assertEquals("alice", dto.name);

        Request bad = request("not-json");
        HttpException ex = assertThrows(HttpException.class, () -> json.body(bad, UserDto.class));
        assertEquals(400, ex.status());
        assertTrue(ex.getMessage().startsWith("Invalid request body:"));
    }

    @Test
    void bodyFrom_parses_stream_and_maps_bad_payload_to_http_400() throws Exception {
        Json json = new Json(new ObjectMapper());

        Request ok = request("{\"id\":\"u2\",\"name\":\"bob\"}");
        UserDto dto = json.bodyFrom(ok, UserDto.class);
        assertEquals("u2", dto.id);
        assertEquals("bob", dto.name);

        Request bad = request("[");
        HttpException ex = assertThrows(HttpException.class, () -> json.bodyFrom(bad, UserDto.class));
        assertEquals(400, ex.status());
    }

    @Test
    void write_and_writeView_set_content_type_and_render_expected_payload() throws Exception {
        Json json = new Json(new ObjectMapper());
        Response res = new Response(200, ContentType.TEXT_PLAIN);

        String payload = json.write(res, new UserDto("u3", "carol"));
        assertEquals("application/json", new String(res.getContentType(), StandardCharsets.UTF_8));
        assertEquals("{\"id\":\"u3\",\"name\":\"carol\"}", payload);

        Response viewRes = new Response(200, ContentType.TEXT_PLAIN);
        String viewed = json.writeView(viewRes, new ViewDto("u4", "hidden"), PublicView.class);
        assertEquals("application/json", new String(viewRes.getContentType(), StandardCharsets.UTF_8));
        assertEquals("{\"id\":\"u4\"}", viewed);
    }

    @Test
    void mapper_returns_underlying_object_mapper_instance() {
        ObjectMapper mapper = new ObjectMapper();
        Json json = new Json(mapper);
        assertSame(mapper, json.mapper());
    }

    private static Request request(String body) {
        return request(body.getBytes(StandardCharsets.UTF_8));
    }

    private static Request request(byte[] body) {
        RequestLine line = new RequestLine(
                HttpMethod.POST,
                new FastPathViews.StringByteView("/json"),
                null,
                new FastPathViews.StringByteView("HTTP/1.1"),
                new HeaderMap()
        );
        return new Request(line, body);
    }

    private static final class UserDto {
        public String id;
        public String name;

        public UserDto() {}

        private UserDto(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private interface PublicView {}
    private interface InternalView {}

    private static final class ViewDto {
        @JsonView(PublicView.class)
        public String id;
        @JsonView(InternalView.class)
        public String secret;

        private ViewDto(String id, String secret) {
            this.id = id;
            this.secret = secret;
        }
    }
}
