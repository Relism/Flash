package dev.relism.flash.ext.vite;

import dev.relism.flash.App;
import dev.relism.flash.Bound;
import dev.relism.flash.Module;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.Services;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http.StaticFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static dev.relism.flash.bytes.ByteScan.indexOfIgnoreCase;

/**
 * A Vite frontend for a Flash app. In dev ({@link ServerConfig#isDev()}) it runs Vite's dev server
 * beside the app, which the browser talks to and which proxies the backend's paths to Flash.
 * Otherwise it serves the build that {@code flash-ext-vite-maven-plugin} packaged into the jar, as a
 * single-page app.
 */
public final class ViteModule implements Module {

    /** Where the build lives on the classpath: the Maven plugin puts it there, production reads it from there. */
    public static final String CLASSPATH = "flash-vite";

    private Path root = Path.of("web");
    private int devPort = 5173;
    private String basePath = "/";
    private boolean navigationOnly = true;

    /** The Vite project, {@code web} (relative to the working directory) by default. Read in dev only. */
    public ViteModule root(Path root) {
        this.root = root;
        return this;
    }

    /** Vite's port in dev, 5173 by default. */
    public ViteModule devPort(int devPort) {
        this.devPort = devPort;
        return this;
    }

    /** Where the frontend is served from, {@code /} by default; Vite's {@code base} has to match it. */
    public ViteModule basePath(String basePath) {
        String trimmed = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.basePath = trimmed.isEmpty() ? "/" : trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        return this;
    }

    /**
     * Whether only a browser navigation to a path that is no file gets {@code index.html}, {@code
     * true} by default, so an API call to a missing route gets a 404 rather than the page. {@code
     * false} serves the page for every such {@code GET}, as nginx's {@code try_files} would.
     */
    public ViteModule navigationOnly(boolean navigationOnly) {
        this.navigationOnly = navigationOnly;
        return this;
    }

    @Override
    public void install(App app) {
        Frontend frontend = new Frontend(root, devPort, basePath, navigationOnly);
        app.add(Frontend.class, frontend).get("/".equals(basePath) ? "/**" : basePath + "/**", frontend);
    }

    /** The build from memory, or, in dev, Vite's dev server for as long as the app runs. */
    private static final class Frontend implements Handler, Bound, AutoCloseable {

        private static final byte[] HTML = "text/html".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] NAVIGATE = "navigate".getBytes(StandardCharsets.US_ASCII);

        private final Path root;
        private final int devPort;
        private final String basePath;
        private final boolean navigationOnly;
        private StaticFiles files;
        private DevServer devServer;

        Frontend(Path root, int devPort, String basePath, boolean navigationOnly) {
            this.root = root;
            this.devPort = devPort;
            this.basePath = basePath;
            this.navigationOnly = navigationOnly;
        }

        @Override
        public void bind(Services services) {
            if (services.get(ServerConfig.class).isDev()) {
                devServer = new DevServer(root, devPort);
                return;
            }
            ClassLoader loader = ViteModule.class.getClassLoader();
            if (loader.getResource(CLASSPATH + "/index.html") == null) {
                throw new IllegalStateException("No built frontend on the classpath (" + CLASSPATH + "/index.html): "
                        + "package the application with flash-ext-vite-maven-plugin, or run it with FLASH_ENV=dev.");
            }
            // Vite puts every content-hashed file under assets/.
            files = new StaticFiles(loader, CLASSPATH, basePath, name -> name.startsWith("assets/"));
        }

        /**
         * The file at the request's path. A path that is none gets {@code index.html} when a browser
         * navigates to it, so the client-side router takes it; anything else, an API call or a
         * missing script, gets the app's own 404. In dev Vite answers the browser, so nothing is here.
         */
        @Override
        public Object handle(Request req, Response res) {
            if (files == null) throw HttpException.notFound(req.path());
            if (files.serve(req.path(), req, res)) return null;
            if (navigationOnly && !navigation(req)) throw HttpException.notFound(req.path());
            files.serve(basePath, req, res);
            return null;
        }

        @Override
        public void close() {
            if (devServer != null) devServer.close();
        }

        /** What browsers send when a person opens a URL: {@code Sec-Fetch-Mode}, or {@code Accept} naming HTML for older clients. */
        private static boolean navigation(Request req) {
            return indexOfIgnoreCase(req.headerView("Sec-Fetch-Mode"), NAVIGATE, 0) >= 0
                    || indexOfIgnoreCase(req.headerView("Accept"), HTML, 0) >= 0;
        }
    }
}
