package dev.relism.flash.template;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ByteTemplateTest {

    // --- render ---

    @Test
    void render_singlePlaceholder() {
        ByteTemplate tpl = new ByteTemplate("Hello {{name}}!");
        byte[] result = tpl.render("name", "World");
        assertEquals("Hello World!", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void render_multiplePlaceholders() {
        ByteTemplate tpl = new ByteTemplate("{{greeting}} {{name}}, welcome to {{place}}");
        byte[] result = tpl.render(
                "greeting", "Hi",
                "name", "Alice",
                "place", "Wonderland"
        );
        assertEquals("Hi Alice, welcome to Wonderland", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void render_repeatedPlaceholder() {
        ByteTemplate tpl = new ByteTemplate("{{var}} == {{var}}");
        byte[] result = tpl.render("var", "test");
        assertEquals("test == test", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void render_unmatchedPlaceholder_leavesEmptySpace() {
        ByteTemplate tpl = new ByteTemplate("A{{foo}}B");
        byte[] result = tpl.render("bar", "baz"); // foo is missing
        assertEquals("AB", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void render_noPlaceholders_returnsIdenticalOutput() {
        ByteTemplate tpl = new ByteTemplate("Static Content Only");
        byte[] result = tpl.render("ignored", "value");
        assertEquals("Static Content Only", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void render_adjacentPlaceholders() {
        ByteTemplate tpl = new ByteTemplate("A{{v1}}{{v2}}B");
        byte[] result = tpl.render("v1", "1", "v2", "2");
        assertEquals("A12B", new String(result, StandardCharsets.UTF_8));
    }
}
