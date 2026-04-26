package dev.relism.flash.ext.view.jte;

import dev.relism.flash.ext.view.core.BaseViewExtension;
import dev.relism.flash.ext.view.core.GlobalValue;
import dev.relism.flash.ext.view.core.ViewRuntimeBridge;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Opinionated jte SSR extension for Flash.
 */
public final class JteExtension extends BaseViewExtension<JteTarget> {
    private final JteSettings settings;

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

    private static void ensureJtePresent() {
        try {
            Class.forName("gg.jte.TemplateEngine", false, JteExtension.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("jte is not on the classpath. Add dependency gg.jte:jte:3.2.3", e);
        }
    }
}
