package dev.relism.flash.ext.view.thymeleaf;

import dev.relism.flash.ext.view.core.GlobalValue;
import dev.relism.flash.ext.view.core.RenderedView;
import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.routing.GET;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import org.junit.jupiter.api.Test;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThymeleafRuntimeTest {

    @Test
    void render_template_resolvesTemplateAndLinks() throws Exception {
        ThymeleafRuntime runtime = new ThymeleafRuntime(ThymeleafSettings.builder().cacheEnabled(false).build(), List.of());

        RenderedView out = runtime.render(new ThymeleafHandler() {
            @Override
            public ViewModel render(Request req) {
                return ViewModel.empty().with("title", "Home").with("id", 42).with("page", 2);
            }
        }, new ThymeleafTarget(ThymeleafTarget.Kind.TEMPLATE, "pages/home", "", ContentType.TEXT_HTML), null, new Response(200, ContentType.JSON));

        assertEquals(ContentType.TEXT_HTML, out.contentType());
        assertTrue(out.body().contains("Home"));
        assertTrue(out.body().contains("/users/42?page=2"));
    }

    @Test
    void render_fragment_usesExplicitFragment() throws Exception {
        ThymeleafRuntime runtime = new ThymeleafRuntime(ThymeleafSettings.builder().cacheEnabled(false).build(), List.of());

        RenderedView out = runtime.render(new ThymeleafHandler() {
            @Override
            public ViewModel render(Request req) {
                return ViewModel.empty().with("id", 42);
            }
        }, new ThymeleafTarget(ThymeleafTarget.Kind.FRAGMENT, "pages/home", "rows", ContentType.TEXT_HTML), null, new Response(200, ContentType.JSON));

        assertTrue(out.body().contains("row-42"));
    }

    @Test
    void render_mergesGlobalsInReservedNamespace() throws Exception {
        ThymeleafRuntime runtime = new ThymeleafRuntime(
                ThymeleafSettings.builder().cacheEnabled(false).build(),
                List.of(new GlobalValue("appName", req -> "Flash"), new GlobalValue("path", req -> "/x"))
        );

        RenderedView out = runtime.render(new ThymeleafHandler() {
            @Override
            public ViewModel render(Request req) {
                return ViewModel.of("title", "Home");
            }
        }, new ThymeleafTarget(ThymeleafTarget.Kind.TEMPLATE, "pages/with-globals", "", ContentType.TEXT_HTML), null, new Response(200, ContentType.JSON));

        assertTrue(out.body().contains("Flash"));
        assertTrue(out.body().contains("/x"));
    }

    @Test
    void render_rejectsLocalGlobalKeyOverride() {
        ThymeleafRuntime runtime = new ThymeleafRuntime(
                ThymeleafSettings.builder().cacheEnabled(false).build(),
                List.of(new GlobalValue("appName", req -> "Flash"))
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                runtime.render(new ThymeleafHandler() {
                    @Override
                    public ViewModel render(Request req) {
                        return ViewModel.of("global", "bad");
                    }
                }, new ThymeleafTarget(ThymeleafTarget.Kind.TEMPLATE, "pages/home", "", ContentType.TEXT_HTML), null, new Response(200, ContentType.JSON))
        );

        assertTrue(ex.getMessage().contains("reserved"));
    }

    @Test
    void render_template_withoutParams_linkBuilderCoversEmptyBranches() throws Exception {
        ThymeleafRuntime runtime = new ThymeleafRuntime(ThymeleafSettings.builder().cacheEnabled(false).build(), List.of());

        RenderedView out = runtime.render(new ThymeleafHandler() {
            @Override
            public ViewModel render(Request req) {
                return ViewModel.of("title", "NoParams");
            }
        }, new ThymeleafTarget(ThymeleafTarget.Kind.TEMPLATE, "pages/link-cases", "", ContentType.TEXT_HTML), null, new Response(200, ContentType.JSON));

        assertTrue(out.body().contains("/static"));
        assertTrue(out.body().contains("/users/"));
        assertTrue(out.body().contains("/search"));
    }

    @Test
    void resolve_cachesTargetInstance() {
        ThymeleafRuntime runtime = new ThymeleafRuntime(ThymeleafSettings.builder().cacheEnabled(false).build(), List.of());

        ThymeleafTarget first = runtime.resolve(CachedTemplateHandler.class);
        ThymeleafTarget second = runtime.resolve(CachedTemplateHandler.class);

        assertTrue(first == second);
    }

    @Test
    void resolve_missingViewAnnotation_fails() {
        ThymeleafRuntime runtime = new ThymeleafRuntime(ThymeleafSettings.builder().cacheEnabled(false).build(), List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> runtime.resolve(MissingViewHandler.class));
        assertTrue(ex.getMessage().contains("must declare @Template or @Fragment"));
    }

    @Test
    void builderBranches_coverPrefixSuffixFragmentModeAndCacheFlag() {
        ThymeleafSettings settings = ThymeleafSettings.builder()
                .prefix("templates")
                .suffix("html")
                .mode(TemplateMode.HTML)
                .cacheEnabled(false)
                .defaultFragment("rows")
                .build();

        assertEquals("templates/", settings.prefix());
        assertEquals(".html", settings.suffix());
        assertEquals("rows", settings.defaultFragment());
        assertFalse(settings.cacheEnabled());
    }

    @GET("/cached")
    @Template("pages/home")
    static class CachedTemplateHandler extends ThymeleafHandler {
        @Override
        public ViewModel render(Request req) {
            return ViewModel.of("title", "cached").with("id", 1).with("page", 1);
        }
    }

    static class MissingViewHandler extends ThymeleafHandler {
        @Override
        public ViewModel render(Request req) {
            return ViewModel.empty();
        }
    }
}
