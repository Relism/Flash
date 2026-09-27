package dev.relism.flash.ext.view.jte;

import dev.relism.flash.App;
import dev.relism.flash.Bound;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.Services;
import dev.relism.flash.ext.view.core.ViewEngine;
import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.ext.view.core.ViewModule;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http.StaticFiles;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.Utf8ByteOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import gg.jte.resolve.ResourceCodeResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * jte templates for {@link dev.relism.flash.ext.view.core.ViewHandler}s named with {@link Template},
 * rendered straight to UTF-8 bytes, and the classpath's {@code static/} directory served under
 * {@code /static}. Templates are compiled at build time outside dev and on the fly in dev.
 *
 * <pre>{@code
 * app.install(new JteModule().global("user", req -> currentUser(req)));
 * }</pre>
 */
public final class JteModule extends ViewModule {

    private String templateRoot = "templates";
    private ContentType contentType = ContentType.Html;
    private Boolean precompiled;
    private Path classes = Path.of("jte-classes");
    private String staticPrefix = "/static";

    /** Where templates are read in dev: a directory if one exists at that path, the classpath otherwise; {@code templates} by default. */
    public JteModule templateRoot(String templateRoot) {
        this.templateRoot = templateRoot.replaceAll("^/+|/+$", "");
        return this;
    }

    /** HTML (escaped output) by default, or plain text. */
    public JteModule contentType(ContentType contentType) {
        this.contentType = contentType;
        return this;
    }

    /** Whether templates were compiled at build time; by default they were, outside dev. */
    public JteModule precompiled(boolean precompiled) {
        this.precompiled = precompiled;
        return this;
    }

    /** Where compiled template classes are written in dev and read otherwise, {@code jte-classes} by default. */
    public JteModule classes(Path classes) {
        this.classes = classes;
        return this;
    }

    /** Where the classpath's {@code static/} directory is served, {@code /static} by default; null serves none. */
    public JteModule statics(String prefix) {
        this.staticPrefix = prefix;
        return this;
    }

    @Override
    public JteModule global(String key, Function<Request, Object> resolver) {
        super.global(key, resolver);
        return this;
    }

    @Override
    public void install(App app) {
        super.install(app);
        ClassLoader loader = JteModule.class.getClassLoader();
        if (staticPrefix != null && loader.getResource("static") != null) {
            app.get(staticPrefix + "/**", new StaticFiles(loader, "static", staticPrefix, StaticFiles.HASHED));
        }
    }

    @Override
    protected ViewEngine engine(Map<String, Function<Request, Object>> globals) {
        return new Engine(templateRoot, contentType, precompiled, classes, globals);
    }

    private static final class Engine implements ViewEngine, Bound {

        private final String templateRoot;
        private final ContentType contentType;
        private final Boolean precompiled;
        private final Path classes;
        private final Map<String, Function<Request, Object>> globals;
        private final byte[] type;
        private TemplateEngine jte;

        Engine(String templateRoot, ContentType contentType, Boolean precompiled, Path classes, Map<String, Function<Request, Object>> globals) {
            this.templateRoot = templateRoot;
            this.contentType = contentType;
            this.precompiled = precompiled;
            this.classes = classes;
            this.globals = globals;
            this.type = dev.relism.flash.http.ContentType.ofExtension(contentType == ContentType.Plain ? "txt" : "html")
                    .getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public void bind(Services services) {
            if (precompiled != null ? precompiled : !services.get(ServerConfig.class).isDev()) {
                jte = Files.isDirectory(classes) ? TemplateEngine.createPrecompiled(classes, contentType) : TemplateEngine.createPrecompiled(contentType);
            } else {
                Path directory = Path.of(templateRoot);
                jte = TemplateEngine.create(Files.isDirectory(directory) ? new DirectoryCodeResolver(directory)
                        : new ResourceCodeResolver(templateRoot, JteModule.class.getClassLoader()), classes, contentType);
            }
            jte.setBinaryStaticContent(true);
        }

        @Override
        public Object target(Class<?> handler) {
            Template template = handler.getAnnotation(Template.class);
            if (template == null) return null;
            if (template.value().isBlank()) throw new IllegalStateException(handler.getName() + " names a blank @Template");
            return template.value();
        }

        @Override
        public Object render(Object target, ViewModel model, Request req, Response res) {
            Map<String, Object> values = new HashMap<>(model == null ? Map.of() : model.toMap());
            globals.forEach((key, resolver) -> {
                if (values.putIfAbsent(key, ViewModel.unwrapValue(resolver.apply(req))) != null)
                    throw new IllegalStateException("ViewModel key '" + key + "' conflicts with a registered global");
            });
            Utf8ByteOutput out = new Utf8ByteOutput();
            jte.render((String) target, values, out);
            res.type(type);
            return out.toByteArray();
        }
    }
}
