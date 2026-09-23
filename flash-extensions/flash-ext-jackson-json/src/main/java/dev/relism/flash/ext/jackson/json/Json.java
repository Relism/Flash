package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.ext.jackson.Codec;
import dev.relism.flash.http.ContentType;

/**
 * JSON in and out, checked against the body type's own constraints.
 *
 * <pre>{@code
 * public final class CreateItem extends RequestHandler {
 *     @Inject private Json json;
 *
 *     @Override public Object handle(Request req, Response res) throws Exception {
 *         return items.create(json.body(req, NewItem.class));
 *     }
 * }
 * }</pre>
 *
 * <p>A handler whose whole body is one type has nothing to write at all: see {@link JsonHandler}.
 */
public final class Json extends Codec {

    public Json(ObjectMapper mapper) {
        super(mapper, ContentType.JSON);
    }
}
