package dev.relism.ext.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.extension.FlashContext;
import dev.relism.http.ContentType;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonExtensionTest {

    @Test
    void provide_registers_json_mapper_and_middleware() {
        FlashContext ctx = new FlashContext();
        ObjectMapper mapper = new ObjectMapper();
        JacksonExtension ext = new JacksonExtension(mapper);

        ext.provide(ctx);

        assertNotNull(ctx.require(Json.class));
        assertNotNull(ctx.require(JacksonMiddleware.class));
        assertSame(mapper, ctx.require(ObjectMapper.class));
    }

    @Test
    void autoJson_factory_delegates_to_middleware_policy() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JacksonExtension ext = new JacksonExtension(mapper);
        RequestHandler next = new RequestHandler() {
            @Override
            public Object handle(Request request, Response response) {
                return new Payload("ok");
            }
        };
        RequestHandler wrapped = new RequestHandler() {
            private final dev.relism.models.SimpleHandler.FunctionalHandler delegate = ext.autoJson().wrap(next);

            @Override
            public Object handle(Request request, Response response) throws Exception {
                return delegate.handle(request, response);
            }
        };

        Response res = new Response(200, ContentType.TEXT_PLAIN);
        Object out = wrapped.handle(null, res);

        assertTrue(out instanceof byte[]);
        assertEquals("application/json", new String(res.getContentType(), StandardCharsets.UTF_8));
    }

    private record Payload(String status) {}
}
