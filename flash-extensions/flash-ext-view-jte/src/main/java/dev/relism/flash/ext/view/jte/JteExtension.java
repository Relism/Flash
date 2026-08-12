package dev.relism.flash.ext.view.jte;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.ext.view.core.BaseViewExtension;
import dev.relism.flash.ext.view.core.GlobalValue;
import dev.relism.flash.ext.view.core.ViewRuntimeBridge;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public final class JteExtension extends BaseViewExtension<JteTarget> {
    private final JteSettings settings;

    static {
        ensureJtePresent();
    }

    public JteExtension() {
        this.settings = JteSettings.builder().build();
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
        return new JteExtension(settings.toBuilder().templateRoot(templateRoot).build());
    }

    public JteExtension serveStatics(boolean serveStatics) {
        return new JteExtension(settings.toBuilder().serveStatics(serveStatics).build());
    }

    public JteExtension staticPrefix(String staticPrefix) {
        return new JteExtension(settings.toBuilder().staticPrefix(staticPrefix).build());
    }

    public JteExtension withStaticCors() {
        return new JteExtension(settings.toBuilder().enableStaticCors(true).build());
    }

    public JteExtension staticCors(Consumer<JteSettings.Builder> corsConfig) {
        JteSettings.Builder builder = settings.toBuilder();
        builder.enableStaticCors(true);
        corsConfig.accept(builder);
        return new JteExtension(builder.build());
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        super.configure(app, ctx);
        ctx.onReady(() -> {
            if (!settings.serveStatics()) return;
            JteStaticServing staticServing = JteStaticServing.load(settings);
            if (staticServing == null) return;
            String wildcard = settings.staticPrefix() + "/**";
            StaticJteHandler handler = new StaticJteHandler(staticServing);
            app.get(wildcard, handler::handle);
            app.head(wildcard, (req, res) -> { staticServing.serve(req, res, true); return null; });
        });
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

    JteSettings getSettings() {
        return settings;
    }

    private static final class StaticJteHandler extends dev.relism.flash.models.RequestHandler {
        private final JteStaticServing serving;

        StaticJteHandler(JteStaticServing serving) {
            this.serving = serving;
        }

        @Override
        public Object handle(dev.relism.flash.models.Request req, dev.relism.flash.models.Response res) {
            serving.serve(req, res, false);
            return null;
        }
    }

    private static void ensureJtePresent() {
        try {
            Class.forName("gg.jte.TemplateEngine", false, JteExtension.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("jte is not on the classpath. Add dependency gg.jte:jte:3.2.3", e);
        }
    }
}
