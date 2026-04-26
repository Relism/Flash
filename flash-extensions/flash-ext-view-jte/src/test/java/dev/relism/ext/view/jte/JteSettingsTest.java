package dev.relism.ext.view.jte;

import dev.relism.Flash;
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
        assertTrue(settings.serveStatics());
        assertEquals("/static", settings.staticPrefix());
        assertEquals(64L * 1024L, settings.largeFileThresholdBytes());
        assertFalse(settings.staticCorsEnabled());
        assertEquals("*", settings.staticCorsAllowOrigin());
        assertEquals("GET,HEAD,OPTIONS", settings.staticCorsAllowMethods());
        assertEquals("*", settings.staticCorsAllowHeaders());
        assertEquals(3600, settings.staticCorsMaxAge());
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
                .serveStatics(false)
                .staticPrefix("assets")
                .largeFileThresholdBytes(1024)
                .enableStaticCors(true)
                .staticCorsAllowOrigin("https://cdn.example.com")
                .staticCorsAllowMethods("GET,HEAD")
                .staticCorsAllowHeaders("Origin,Content-Type")
                .staticCorsMaxAge(120)
                .build();

        assertEquals("/src/main/jte", settings.templateRoot());
        assertEquals(gg.jte.ContentType.Plain, settings.contentType());
        assertTrue(settings.developmentMode());
        assertTrue(settings.usePrecompiled());
        assertTrue(settings.binaryStaticContent());
        assertEquals(Path.of("var", "jte-dev"), settings.dynamicClassesPath());
        assertEquals(Path.of("var", "jte-prod"), settings.precompiledClassesPath());
        assertFalse(settings.serveStatics());
        assertEquals("/assets", settings.staticPrefix());
        assertEquals(1024, settings.largeFileThresholdBytes());
        assertTrue(settings.staticCorsEnabled());
        assertEquals("https://cdn.example.com", settings.staticCorsAllowOrigin());
        assertEquals("GET,HEAD", settings.staticCorsAllowMethods());
        assertEquals("Origin,Content-Type", settings.staticCorsAllowHeaders());
        assertEquals(120, settings.staticCorsMaxAge());
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
    void staticPrefix_normalizes_andRejectsBlank() {
        assertEquals("/static", JteSettings.builder().staticPrefix("static/").build().staticPrefix());
        assertEquals("/assets", JteSettings.builder().staticPrefix("/assets/").build().staticPrefix());
        assertThrows(IllegalArgumentException.class, () -> JteSettings.builder().staticPrefix("   "));
    }

    @Test
    void nulls_areRejectedForRequiredObjects() {
        assertThrows(NullPointerException.class, () -> JteSettings.builder().contentType(null));
        assertThrows(NullPointerException.class, () -> JteSettings.builder().dynamicClassesPath(null));
        assertThrows(NullPointerException.class, () -> JteSettings.builder().precompiledClassesPath(null));
        assertThrows(IllegalArgumentException.class, () -> JteSettings.builder().largeFileThresholdBytes(0));
        assertThrows(IllegalArgumentException.class, () -> JteSettings.builder().staticCorsAllowOrigin(" "));
        assertThrows(IllegalArgumentException.class, () -> JteSettings.builder().staticCorsAllowMethods(" "));
        assertThrows(IllegalArgumentException.class, () -> JteSettings.builder().staticCorsAllowHeaders(" "));
        assertThrows(IllegalArgumentException.class, () -> JteSettings.builder().staticCorsMaxAge(-1));
    }
}
