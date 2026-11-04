package dev.relism.flash.ext.data.core;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import io.avaje.jsonb.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The adapter this jar registers: an application on Avaje Jsonb writes a page with nothing to declare. */
class PageJsonbTest {

    @Test
    void aPageIsWrittenAndReadByTheAdapterThisJarCarries() {
        JsonType<Page<String>> type = Jsonb.builder().build().type(Types.newParameterizedType(Page.class, String.class));
        String json = type.toJson(new Page<>(List.of("a", "b"), "t0"));
        assertEquals("{\"content\":[\"a\",\"b\"],\"next\":\"t0\"}", json);
        assertEquals(new Page<>(List.of("a", "b"), "t0"), type.fromJson(json));
    }

    @Test
    void theLastPageSaysNextIsNullThoughJsonbLeavesNullsOut() {
        JsonType<Page<String>> type = Jsonb.builder().build().type(Types.newParameterizedType(Page.class, String.class));
        assertEquals("{\"content\":[\"a\"],\"next\":null}", type.toJson(new Page<>(List.of("a"), null)));
        assertEquals(new Page<>(List.of("a"), null), type.fromJson("{\"content\":[\"a\"],\"next\":null}"));
    }
}
