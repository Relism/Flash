package dev.relism.flash.ext.routeviewer;

import dev.relism.flash.App;
import dev.relism.flash.Bound;
import dev.relism.flash.Module;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.Services;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http.StaticFiles;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.RouteHook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Every route of the app as a draggable graph (routes, their middleware, their handler's class
 * hierarchy) at {@code /routeviewer}, in dev only: outside dev the path is a 404.
 *
 * <pre>{@code
 * app.install(new RouteViewerModule());
 * }</pre>
 */
public final class RouteViewerModule implements Module {

    private final String path;

    public RouteViewerModule() {
        this("/routeviewer");
    }

    public RouteViewerModule(String path) {
        this.path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    @Override
    public void install(App app) {
        Viewer viewer = new Viewer(path);
        app.add(RouteHook.class, viewer).get(path + "/**", viewer);
    }

    /** The UI bundled in the jar, and the graph of the routes it saw built. */
    private static final class Viewer implements Handler, RouteHook, Bound {

        private final String path;
        private final String data;
        private final List<Route> routes = new ArrayList<>();
        private StaticFiles ui;
        private byte[] json;

        Viewer(String path) {
            this.path = path;
            this.data = path + "/data";
        }

        @Override
        public void bind(Services services) {
            if (services.get(ServerConfig.class).isDev()) {
                ui = new StaticFiles(RouteViewerModule.class.getClassLoader(), "routeviewer", path, name -> false);
            }
        }

        @Override
        public void routed(Route route) {
            routes.add(route);
        }

        @Override
        public Object handle(Request req, Response res) {
            if (ui == null) throw HttpException.notFound(req.path());
            if (req.path().equals(data)) {
                // Every route is built before the first request.
                if (json == null) json = GraphSerializer.toJson(routes).getBytes(StandardCharsets.UTF_8);
                res.type(ContentType.JSON).header("Cache-Control", "no-cache");
                return json;
            }
            if (!ui.serve(req.path(), req, res)) throw HttpException.notFound(req.path());
            return null;
        }
    }
}
