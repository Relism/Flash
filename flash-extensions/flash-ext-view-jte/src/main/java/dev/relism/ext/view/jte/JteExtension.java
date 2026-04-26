package dev.relism.ext.view.jte;

import dev.relism.ext.view.core.BaseViewExtension;
import dev.relism.ext.view.core.GlobalValue;
import dev.relism.ext.view.core.ViewRuntimeBridge;
import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashRegistrar;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.routing.Middleware;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Opinionated jte SSR extension for Flash.
 */
public final class JteExtension extends BaseViewExtension<JteTarget> {
    private JteSettings settings;

    static {
        ensureJtePresent();
    }

    public JteExtension() {
        this(JteSettings.builder().build());
    }

    public JteExtension(Consumer<JteSettings.Builder> customizer) {
        JteSettings.Builder builder = JteSettings.builder();
        java.util.Objects.requireNonNull(customizer, "customizer must not be null").accept(builder);
        this.settings = builder.build();
    }

    private JteExtension(JteSettings settings) {
        this.settings = settings;
    }

    public JteExtension templateRoot(String templateRoot) {
        settings = settings.toBuilder().templateRoot(templateRoot).build();
        return this;
    }

    public JteExtension serveStatics(boolean enabled) {
        settings = settings.toBuilder().serveStatics(enabled).build();
        return this;
    }

    public JteExtension withStaticServing() {
        return serveStatics(true);
    }

    public JteExtension staticPrefix(String staticPrefix) {
        settings = settings.toBuilder().staticPrefix(staticPrefix).build();
        return this;
    }

    public JteExtension staticCors(Consumer<JteSettings.Builder> customizer) {
        java.util.Objects.requireNonNull(customizer, "customizer must not be null");
        JteSettings.Builder b = settings.toBuilder();
        customizer.accept(b);
        settings = b.build();
        return this;
    }

    public JteExtension withStaticCors() {
        settings = settings.toBuilder().enableStaticCors(true).build();
        return this;
    }

    @Override
    public JteExtension addGlobal(String key, Function<Request, Object> resolver) {
        super.addGlobal(key, resolver);
        return this;
    }

    @Override
    protected ViewRuntimeBridge<JteTarget> createRuntime(List<GlobalValue> globals) {
        return new JteRuntime(settings, globals);
    }

    @Override
    protected void validateHandlerClass(Class<? extends RequestHandler> handlerClass) {
        JteTarget target = JteTargetResolver.resolve(handlerClass, settings);
        if (target == null) {
            if (JteHandler.class.isAssignableFrom(handlerClass)) {
                throw new IllegalStateException("JteHandler " + handlerClass.getName() + " must declare @Template");
            }
            return;
        }
        if (!JteHandler.class.isAssignableFrom(handlerClass)) {
            throw new IllegalStateException("Handler " + handlerClass.getName()
                    + " declares @Template but does not extend JteHandler");
        }
    }

    @Override
    public void routes(FlashRegistrar<?> app, FlashContext ctx) {
        if (!settings.serveStatics()) return;

        JteStaticServing staticServing = JteStaticServing.load(settings);
        if (staticServing == null) return;

        String prefix = settings.staticPrefix();
        String wildcard = "/".equals(prefix) ? "/**" : prefix + "/**";
        Middleware cors = staticCorsMiddleware();

        if (settings.staticCorsEnabled()) {
            app.options(wildcard, (req, res) -> {
                applyCors(req, res);
                res.status(204);
                return null;
            });
        }

        if (cors != null) {
            app.get(wildcard, (req, res) -> staticServing.serve(req, res, false) ? null : notFound(res), cors);
            app.head(wildcard, (req, res) -> staticServing.serve(req, res, true) ? null : notFound(res), cors);
        } else {
            app.get(wildcard, (req, res) -> staticServing.serve(req, res, false) ? null : notFound(res));
            app.head(wildcard, (req, res) -> staticServing.serve(req, res, true) ? null : notFound(res));
        }
    }

    private Middleware staticCorsMiddleware() {
        if (!settings.staticCorsEnabled()) return null;
        return next -> (req, res) -> {
            applyCors(req, res);
            return next.handle(req, res);
        };
    }

    private Object notFound(dev.relism.models.Response res) {
        res.status(404);
        return null;
    }

    private void applyCors(dev.relism.models.Request req, dev.relism.models.Response res) {
        String origin = req.header("Origin");
        if (origin == null || origin.isBlank()) return;
        res.header("Access-Control-Allow-Origin", settings.staticCorsAllowOrigin());
        res.header("Access-Control-Allow-Methods", settings.staticCorsAllowMethods());
        res.header("Access-Control-Allow-Headers", settings.staticCorsAllowHeaders());
        res.header("Access-Control-Max-Age", Integer.toString(settings.staticCorsMaxAge()));
    }

    private static void ensureJtePresent() {
        try {
            Class.forName("gg.jte.TemplateEngine", false, JteExtension.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("jte is not on the classpath. Add dependency gg.jte:jte:3.2.3", e);
        }
    }
}
