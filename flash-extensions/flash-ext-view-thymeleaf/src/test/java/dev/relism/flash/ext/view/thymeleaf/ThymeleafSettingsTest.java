package dev.relism.flash.ext.view.thymeleaf;

import dev.relism.flash.Flash;
import org.junit.jupiter.api.Test;
import org.thymeleaf.templatemode.TemplateMode;

import static org.junit.jupiter.api.Assertions.*;

class ThymeleafSettingsTest {

    @Test
    void defaults_matchFlashConventions() {
        ThymeleafSettings settings = ThymeleafSettings.builder().build();

        assertEquals("/templates/", settings.prefix());
        assertEquals(".html", settings.suffix());
        assertEquals(TemplateMode.HTML, settings.mode());
        assertEquals(!Flash.DEV, settings.cacheEnabled());
        assertEquals("content", settings.defaultFragment());
    }

    @Test
    void builder_normalizesPrefixAndSuffix() {
        ThymeleafSettings settings = ThymeleafSettings.builder()
                .prefix("templates")
                .suffix("jte")
                .mode(TemplateMode.TEXT)
                .cacheEnabled(true)
                .defaultFragment("rows")
                .build();

        assertEquals("templates/", settings.prefix());
        assertEquals(".jte", settings.suffix());
        assertEquals(TemplateMode.TEXT, settings.mode());
        assertTrue(settings.cacheEnabled());
        assertEquals("rows", settings.defaultFragment());
    }

    @Test
    void builder_rejectsBlankValuesAndNullMode() {
        assertThrows(IllegalArgumentException.class, () -> ThymeleafSettings.builder().prefix(" "));
        assertThrows(IllegalArgumentException.class, () -> ThymeleafSettings.builder().suffix(" "));
        assertThrows(IllegalArgumentException.class, () -> ThymeleafSettings.builder().defaultFragment(" "));
        assertThrows(NullPointerException.class, () -> ThymeleafSettings.builder().mode(null));
    }
}
