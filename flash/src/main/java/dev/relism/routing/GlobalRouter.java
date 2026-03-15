package dev.relism.routing;

import dev.relism.exceptions.DuplicateNamespaceException;
import dev.relism.fpr.core.ByteView;
import dev.relism.http.HttpMethod;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.routing.routers.fastpathrouter.FastPathRouterImpl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level dispatcher. Routes to the longest-matching mounted sub-router first,
 * falling back to the internal {@link FastPathRouterImpl}.
 */
public class GlobalRouter extends AbstractRouter {
    private final Map<String, AbstractRouter> subRoutersMap = new HashMap<>();
    private final List<AbstractRouter> sortedSubRouters = new ArrayList<>();
    private final AbstractRouter internalRouter = new FastPathRouterImpl();

    public void mount(String namespace, AbstractRouter router) {
        String sanitized = PathUtils.sanitize(namespace);
        if (subRoutersMap.containsKey(sanitized)) throw new DuplicateNamespaceException(sanitized);

        router.setNamespace(sanitized);
        subRoutersMap.put(sanitized, router);
        sortedSubRouters.add(router);
        sortedSubRouters.sort(Comparator.comparingInt((AbstractRouter r) -> r.getNamespaceBytes().length).reversed());
    }

    @Override
    public RequestHandler route(Request request) {
        ByteView path = request.getRequestLine().getPath();
        for (AbstractRouter sub : sortedSubRouters) {
            if (startsWith(path, sub.getNamespaceBytes())) {
                RequestHandler h = sub.route(request);
                return h != null ? h : sub.getNotFoundHandler();
            }
        }
        RequestHandler h = internalRouter.route(request);
        return h != null ? h : notFoundHandler;
    }

    public ExceptionHandler resolveExceptionHandler(Request request) {
        ByteView path = request.getRequestLine().getPath();
        for (AbstractRouter sub : sortedSubRouters) {
            if (startsWith(path, sub.getNamespaceBytes())) return sub.getExceptionHandler();
        }
        return exceptionHandler;
    }

    @Override
    protected AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler) {
        return internalRouter.addRoute(method, PathUtils.sanitize(path), handler);
    }

    private static boolean startsWith(ByteView view, byte[] prefix) {
        if (view.length() < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (view.byteAt(i) != prefix[i]) return false;
        }
        return true;
    }
}
