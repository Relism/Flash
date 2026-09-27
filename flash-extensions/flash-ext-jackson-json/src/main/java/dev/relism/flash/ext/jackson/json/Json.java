package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.ext.jackson.JacksonCodec;
import dev.relism.flash.http.ContentType;

/**
 * JSON in and out, checked against the body type's own constraints.
 *
 * <pre>{@code
 * public final class CreateItem implements Handler {
 *     private final Json json;
 *     private final Items items;
 *
 *     public CreateItem(Json json, Items items) { this.json = json; this.items = items; }
 *
 *     @Override public Object handle(Request req, Response res) throws Exception {
 *         return json.write(res, items.create(json.read(req, NewItem.class)));
 *     }
 * }
 * }</pre>
 *
 * <p>A handler whose whole body is one type has nothing to write at all: see {@link JsonHandler}.
 */
public final class Json extends JacksonCodec {

    public Json(ObjectMapper mapper) {
        super(mapper, ContentType.JSON);
    }
}
