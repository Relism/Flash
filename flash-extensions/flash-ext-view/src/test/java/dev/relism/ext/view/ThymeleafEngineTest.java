package dev.relism.ext.view;

import dev.relism.http.ContentType;
import dev.relism.models.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThymeleafEngineTest {

    @Test
    void render_page_resolvesTemplateAndLinks() {
        ThymeleafEngine engine = new ThymeleafEngine(false);

        RenderOutput out = engine.render(
                new ViewTarget(ViewKind.PAGE, "pages/home", ""),
                ViewModel.empty().with("title", "Home").with("id", 42).with("page", 2).toMap(),
                null,
                new Response(200, ContentType.JSON)
        );

        assertEquals(ContentType.TEXT_HTML, out.contentType());
        assertTrue(out.body().contains("Home"));
        assertTrue(out.body().contains("/users/42?page=2"));
    }

    @Test
    void render_partial_usesExplicitSlot() {
        ThymeleafEngine engine = new ThymeleafEngine(false);

        RenderOutput out = engine.render(
                new ViewTarget(ViewKind.PARTIAL, "pages/home", "rows"),
                ViewModel.empty().with("id", 42).toMap(),
                null,
                new Response(200, ContentType.JSON)
        );

        assertTrue(out.body().contains("row-42"));
    }

    @Test
    void render_partial_usesDefaultContentSlotWhenBlank() {
        ThymeleafEngine engine = new ThymeleafEngine(false);

        RenderOutput out = engine.render(
                new ViewTarget(ViewKind.PARTIAL, "pages/home", "  "),
                ViewModel.empty().with("title", "ContentSlot").toMap(),
                null,
                new Response(200, ContentType.JSON)
        );

        assertTrue(out.body().contains("content-ContentSlot"));
    }

    @Test
    void capabilities_supportSlotSelection() {
        ThymeleafEngine engine = new ThymeleafEngine(true);
        assertTrue(engine.capabilities().supportsPartialSlot());
    }
}
