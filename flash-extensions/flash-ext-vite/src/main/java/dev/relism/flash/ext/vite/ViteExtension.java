package dev.relism.flash.ext.vite;

import dev.relism.flash.Flash;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;

import java.nio.file.Path;

/**
 * A Vite frontend for a Flash app. In DEV it runs Vite's dev server beside the app, which the
 * browser talks to and which proxies the backend's paths to Flash. Otherwise it serves the build
 * that {@code flash-ext-vite-maven-plugin} packaged into the jar, as a single-page app.
 */
public final class ViteExtension implements FlashExtension {

    /** Where the build lives on the classpath: the Maven plugin puts it there, production reads it from there. */
    public static final String CLASSPATH = "flash-vite";

    private Path root = Path.of("web");
    private int devPort = 5173;
    private String basePath = "/";
    private boolean navigationOnly = true;

    /** The Vite project, {@code web} (relative to the working directory) by default. Read in DEV only. */
    public ViteExtension root(Path root) {
        this.root = root;
        return this;
    }

    /** Vite's port in DEV, 5173 by default. */
    public ViteExtension devPort(int devPort) {
        this.devPort = devPort;
        return this;
    }

    /** Where the frontend is served from, {@code /} by default; Vite's {@code base} has to match it. */
    public ViteExtension basePath(String basePath) {
        String trimmed = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.basePath = trimmed.isEmpty() ? "/" : trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        return this;
    }

    /**
     * Whether only a browser navigation to a path that is no file gets {@code index.html}, {@code
     * true} by default, so an API call to a missing route gets a 404 rather than the page. {@code
     * false} serves the page for every such {@code GET}, as nginx's {@code try_files} would.
     */
    public ViteExtension navigationOnly(boolean navigationOnly) {
        this.navigationOnly = navigationOnly;
        return this;
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        if (Flash.DEV) {
            ctx.onClose(new DevServer(root, devPort)::close);
            return;
        }
        Assets assets = new Assets(ViteExtension.class.getClassLoader(), basePath, navigationOnly);
        String everything = "/".equals(basePath) ? "/**" : basePath + "/**";
        ctx.onReady(() -> {
            app.get(everything, (req, res) -> {
                assets.serve(req, res);
                return null;
            });
            app.head(everything, (req, res) -> {
                assets.serve(req, res);
                return null;
            });
        });
    }
}
