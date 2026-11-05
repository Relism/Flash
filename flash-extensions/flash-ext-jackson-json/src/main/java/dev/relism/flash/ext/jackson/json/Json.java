package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.ext.jackson.JacksonCodec;
import dev.relism.flash.http.ContentType;

/**
 * JSON in and out, through this module's mapper.
 *
 * <p>A handler whose whole body is one type has nothing to write at all: see {@link JsonHandler}.
 */
public final class Json extends JacksonCodec {

    public Json(ObjectMapper mapper) {
        super(mapper, ContentType.JSON);
    }
}
