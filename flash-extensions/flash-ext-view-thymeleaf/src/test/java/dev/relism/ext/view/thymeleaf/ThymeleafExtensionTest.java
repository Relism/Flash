package dev.relism.ext.view.thymeleaf;

import dev.relism.ext.view.core.ViewModel;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;
import dev.relism.routing.GET;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ThymeleafExtensionTest {

    @GET("/ok")
    @Template("pages/home")
    static class ValidTemplateHandler extends ThymeleafHandler {
        @Override
        public ViewModel render(Request req) {
            return ViewModel.of("title", "ok");
        }
    }

    @GET("/fragment")
    @Fragment(template = "pages/home", value = "rows")
    static class ValidFragmentHandler extends ThymeleafHandler {
        @Override
        public ViewModel render(Request req) {
            return ViewModel.of("id", 1);
        }
    }

    @GET("/wrong")
    @Template("pages/home")
    static class WrongBaseHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/missing")
    static class MissingViewAnnotationHandler extends ThymeleafHandler {}

    @Test
    void constructor_requiresNonNullCustomizer() {
        assertThrows(NullPointerException.class, () -> new ThymeleafExtension(null));
    }

    @Test
    void addGlobal_isFluent() {
        ThymeleafExtension ext = new ThymeleafExtension();
        assertSame(ext, ext.addGlobal("app", req -> "Flash"));
    }

    @Test
    void validate_acceptsTemplateAndFragmentHandlers() throws Exception {
        ThymeleafExtension ext = new ThymeleafExtension();
        invokeValidate(ext, ValidTemplateHandler.class);
        invokeValidate(ext, ValidFragmentHandler.class);
    }

    @Test
    void validate_rejectsTemplateWithoutThymeleafBase() {
        ThymeleafExtension ext = new ThymeleafExtension();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invokeValidate(ext, WrongBaseHandler.class));

        assertTrue(ex.getMessage().contains("does not extend ThymeleafHandler"));
    }

    @Test
    void validate_allowsThymeleafBaseWithoutViewAnnotation() throws Exception {
        ThymeleafExtension ext = new ThymeleafExtension();
        invokeValidate(ext, MissingViewAnnotationHandler.class);
    }

    private static void invokeValidate(ThymeleafExtension ext, Class<? extends RequestHandler> type) throws Exception {
        Method m = ThymeleafExtension.class.getDeclaredMethod("validateHandlerClass", Class.class);
        m.setAccessible(true);
        try {
            m.invoke(ext, type);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }
}
