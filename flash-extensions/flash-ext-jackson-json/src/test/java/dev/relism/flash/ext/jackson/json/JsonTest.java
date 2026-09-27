package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import dev.relism.flash.http.Response;
import dev.relism.flash.bytes.FastPathViews;
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
        UserDto dto = json.read(ok, UserDto.class);
        assertEquals("u1", dto.id);
        assertEquals("alice", dto.name);

        Request bad = request("not-json");
        HttpException ex = assertThrows(HttpException.class, () -> json.read(bad, UserDto.class));
        assertEquals(400, ex.status());
        assertTrue(ex.getMessage().startsWith("Invalid request body:"));
    }

    @Test
    void a_truncated_body_is_a_bad_request_too() throws Exception {
        Json json = new Json(new ObjectMapper());

        HttpException ex = assertThrows(HttpException.class, () -> json.read(request("["), UserDto.class));

        assertEquals(400, ex.status());
    }

    @Test
    void write_sets_the_content_type_and_renders_the_payload() throws Exception {
        Json json = new Json(new ObjectMapper());
        Response res = new Response(200, ContentType.TEXT_PLAIN);

        byte[] payload = json.write(res, new UserDto("u3", "carol"));

        assertEquals("application/json", new String(res.getContentType(), StandardCharsets.UTF_8));
        assertEquals("{\"id\":\"u3\",\"name\":\"carol\"}", new String(payload, StandardCharsets.UTF_8));
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
                new Http1HeaderMap()
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
}
