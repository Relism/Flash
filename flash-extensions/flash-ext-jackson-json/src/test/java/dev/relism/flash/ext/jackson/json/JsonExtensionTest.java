package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.extension.FlashContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JsonExtensionTest {

    @Test
    void configure_provides_the_codec_and_the_mapper() {
        FlashContext ctx = new FlashContext();
        ObjectMapper mapper = new ObjectMapper();
        JsonExtension ext = new JsonExtension(mapper);

        ext.configure(null, ctx);
        ctx.complete();

        assertNotNull(ctx.require(Json.class));
        assertSame(mapper, ctx.require(ObjectMapper.class));
    }

}
