package dev.relism.flash.ext.view.jte;

import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.GET;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class JteExtensionTest {

    @GET("/ok")
    @Template("pages/home.jte")
    static class ValidHandler extends JteHandler {
        @Override
        public ViewModel render(Request req) {
            return ViewModel.empty();
        }
    }

    @GET("/wrong")
    @Template("pages/home.jte")
    static class WrongBaseHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return null;
        }
    }

    @GET("/missing")
    static class MissingTemplateHandler extends JteHandler {}

    @Test
    void constructor_requiresNonNullCustomizer() {
        assertThrows(NullPointerException.class, () -> new JteExtension(null));
    }

    @Test
    void validate_acceptsProperHandler() throws Exception {
        JteExtension ext = new JteExtension();
        invokeValidate(ext, ValidHandler.class);
    }

    @Test
    void validate_rejectsTemplateWithoutJteHandlerBase() {
        JteExtension ext = new JteExtension();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invokeValidate(ext, WrongBaseHandler.class));

        assertTrue(ex.getMessage().contains("does not extend JteHandler"));
    }

    @Test
    void validate_rejectsJteHandlerWithoutTemplate() {
        JteExtension ext = new JteExtension();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invokeValidate(ext, MissingTemplateHandler.class));

        assertTrue(ex.getMessage().contains("must declare @Template"));
    }

    private static void invokeValidate(JteExtension ext, Class<? extends RequestHandler> type) throws Exception {
        Method m = JteExtension.class.getDeclaredMethod("validateHandlerClass", Class.class);
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
