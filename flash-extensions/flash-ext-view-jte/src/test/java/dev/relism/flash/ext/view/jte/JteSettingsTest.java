package dev.relism.flash.ext.view.jte;

import dev.relism.flash.Flash;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JteSettingsTest {

    @Test
    void defaults_followFlashDev_andUseHtml() {
        JteSettings settings = JteSettings.builder().build();

        assertEquals("/templates", settings.templateRoot());
        assertEquals(gg.jte.ContentType.Html, settings.contentType());
        assertEquals(Flash.DEV, settings.developmentMode());
        assertEquals(!Flash.DEV, settings.usePrecompiled());
        assertFalse(settings.binaryStaticContent());
        assertEquals(Path.of("jte-classes"), settings.dynamicClassesPath());
        assertEquals(Path.of("jte-classes"), settings.precompiledClassesPath());
    }

    @Test
    void explicitOverrides_winOverDerivedDefaults() {
        JteSettings settings = JteSettings.builder()
                .templateRoot("src/main/jte")
                .contentType(gg.jte.ContentType.Plain)
                .developmentMode(true)
                .usePrecompiled(true)
                .binaryStaticContent(true)
                .dynamicClassesPath(Path.of("var", "jte-dev"))
                .precompiledClassesPath(Path.of("var", "jte-prod"))
                .build();

        assertEquals("/src/main/jte", settings.templateRoot());
        assertEquals(gg.jte.ContentType.Plain, settings.contentType());
        assertTrue(settings.developmentMode());
        assertTrue(settings.usePrecompiled());
        assertTrue(settings.binaryStaticContent());
        assertEquals(Path.of("var", "jte-dev"), settings.dynamicClassesPath());
        assertEquals(Path.of("var", "jte-prod"), settings.precompiledClassesPath());
    }

    @Test
    void usePrecompiled_derivesFromResolvedDevModeWhenMissing() {
        JteSettings dev = JteSettings.builder().developmentMode(true).build();
        JteSettings prod = JteSettings.builder().developmentMode(false).build();

        assertFalse(dev.usePrecompiled());
        assertTrue(prod.usePrecompiled());
    }

    @Test
    void templateRoot_normalizesAndRejectsBlank() {
        JteSettings normalized = JteSettings.builder().templateRoot("templates").build();
        assertEquals("/templates", normalized.templateRoot());

        assertThrows(IllegalArgumentException.class, () -> JteSettings.builder().templateRoot("  "));
    }

    @Test
    void nulls_areRejectedForRequiredObjects() {
        assertThrows(NullPointerException.class, () -> JteSettings.builder().contentType(null));
        assertThrows(NullPointerException.class, () -> JteSettings.builder().dynamicClassesPath(null));
        assertThrows(NullPointerException.class, () -> JteSettings.builder().precompiledClassesPath(null));
    }
}
