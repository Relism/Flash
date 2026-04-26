package dev.relism.flash.ext.view.jte;

import dev.relism.flash.ext.view.core.GlobalValue;
import dev.relism.flash.ext.view.core.RenderedView;
import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.ext.view.jte.model.HomePage;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JteRuntimeTest {

    @Test
    void render_template_rendersPojoAndGlobals() throws Exception {
        JteRuntime runtime = new JteRuntime(
                JteSettings.builder().templateRoot("/templates").developmentMode(true).build(),
                List.of(new GlobalValue("appName", req -> "Flash"))
        );

        RenderedView out = runtime.render(new JteHandler() {
            @Override
            public ViewModel render(Request req) {
                return ViewModel.empty()
                        .with("page", new HomePage("Flash + jte", "elorc"))
                        .with("build", "dev");
            }
        }, new JteTarget("pages/home.jte", ContentType.TEXT_HTML), null, new Response(200, ContentType.JSON));

        assertEquals(ContentType.TEXT_HTML, out.contentType());
        assertTrue(out.body().contains("Flash + jte"));
        assertTrue(out.body().contains("elorc"));
        assertTrue(out.body().contains("Flash"));
    }

    @Test
    void render_rejectsReservedGlobalKeyInLocalModel() {
        JteRuntime runtime = new JteRuntime(
                JteSettings.builder().templateRoot("/templates").developmentMode(true).build(),
                List.of(new GlobalValue("appName", req -> "Flash"))
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                runtime.render(new JteHandler() {
                    @Override
                    public ViewModel render(Request req) {
                        return ViewModel.of("global", "bad");
                    }
                }, new JteTarget("pages/home.jte", ContentType.TEXT_HTML), null, new Response(200, ContentType.JSON))
        );

        assertTrue(ex.getMessage().contains("reserved"));
    }

    @Test
    void resolve_missingTemplateOnHandler_failsFast() {
        JteRuntime runtime = new JteRuntime(JteSettings.builder().templateRoot("/templates").developmentMode(true).build(), List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> runtime.resolve(NoTemplateHandler.class));
        assertTrue(ex.getMessage().contains("must declare @Template"));
    }

    static class NoTemplateHandler extends JteHandler {}
}
