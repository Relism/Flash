package dev.relism.flash.ext.view.thymeleaf;

import dev.relism.flash.ext.view.core.BaseViewExtension;
import dev.relism.flash.ext.view.core.GlobalValue;
import dev.relism.flash.ext.view.core.ViewRuntimeBridge;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;

import java.util.List;
import java.util.function.Consumer;

/**
 * Opinionated Thymeleaf SSR extension for Flash.
 */
public class ThymeleafExtension extends BaseViewExtension<ThymeleafTarget> {
    private final ThymeleafSettings settings;

    static {
        ensureThymeleafPresent();
    }

    public ThymeleafExtension() {
        this(ThymeleafSettings.builder().build());
    }

    public ThymeleafExtension(Consumer<ThymeleafSettings.Builder> customizer) {
        ThymeleafSettings.Builder builder = ThymeleafSettings.builder();
        java.util.Objects.requireNonNull(customizer, "customizer must not be null").accept(builder);
        this.settings = builder.build();
    }

    private ThymeleafExtension(ThymeleafSettings settings) {
        this.settings = settings;
    }

    @Override
    public ThymeleafExtension addGlobal(String key, java.util.function.Function<Request, Object> resolver) {
        super.addGlobal(key, resolver);
        return this;
    }

    @Override
    protected ViewRuntimeBridge<ThymeleafTarget> createRuntime(List<GlobalValue> globals) {
        return new ThymeleafRuntime(settings, globals);
    }

    @Override
    protected void validateHandlerClass(Class<? extends RequestHandler> handlerClass) {
        ThymeleafTarget target = ThymeleafTargetResolver.resolve(handlerClass, settings);
        if (target == null) return;
        if (!ThymeleafHandler.class.isAssignableFrom(handlerClass)) {
            throw new IllegalStateException("Handler " + handlerClass.getName()
                    + " declares @Template/@Fragment but does not extend ThymeleafHandler");
        }
    }

    private static void ensureThymeleafPresent() {
        try {
            Class.forName("org.thymeleaf.TemplateEngine", false, ThymeleafExtension.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Thymeleaf is not on the classpath. Add dependency org.thymeleaf:thymeleaf:3.1.2.RELEASE", e);
        }
    }
}
