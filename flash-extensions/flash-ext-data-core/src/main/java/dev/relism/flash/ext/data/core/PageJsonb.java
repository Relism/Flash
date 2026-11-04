package dev.relism.flash.ext.data.core;

import io.avaje.json.JsonAdapter;
import io.avaje.json.JsonReader;
import io.avaje.json.JsonWriter;
import io.avaje.json.PropertyNames;
import io.avaje.jsonb.Jsonb;
import io.avaje.jsonb.Types;
import io.avaje.jsonb.spi.JsonbComponent;

import java.lang.reflect.Type;
import java.util.List;

/**
 * The Avaje Jsonb adapter of {@link Page}, which Jsonb finds through {@code ServiceLoader}: nothing
 * of it is loaded without Avaje. It writes {@code "next":null} on the last page even when Jsonb
 * leaves nulls out, so a client reads the end the same way with every codec.
 */
public final class PageJsonb implements JsonbComponent {

    @Override
    public void register(Jsonb.Builder builder) {
        builder.add((type, jsonb) -> type == Page.class ? new Adapter<>(jsonb, Object.class)
                : Types.isGenericTypeOf(type, Page.class) ? new Adapter<>(jsonb, Types.typeArguments(type)[0]) : null);
    }

    private static final class Adapter<T> implements JsonAdapter<Page<T>> {
        private final JsonAdapter<List<T>> content;
        private final JsonAdapter<String> next;
        private final PropertyNames names;

        Adapter(Jsonb jsonb, Type item) {
            this.content = jsonb.adapter(Types.listOf(item));
            this.next = jsonb.adapter(String.class);
            this.names = jsonb.properties("content", "next");
        }

        @Override
        public void toJson(JsonWriter writer, Page<T> page) {
            boolean nulls = writer.serializeNulls();
            writer.beginObject(names);
            writer.name(0);
            content.toJson(writer, page.content());
            writer.name(1);
            writer.serializeNulls(true);
            next.toJson(writer, page.next());
            writer.serializeNulls(nulls);
            writer.endObject();
        }

        @Override
        public Page<T> fromJson(JsonReader reader) {
            List<T> items = List.of();
            String after = null;
            reader.beginObject(names);
            while (reader.hasNextField()) {
                switch (reader.nextField()) {
                    case "content" -> items = content.fromJson(reader);
                    case "next" -> after = next.fromJson(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            return new Page<>(items, after);
        }
    }
}
