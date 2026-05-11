package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.PathParams;
import dev.relism.flash.models.Request;
import dev.relism.fpr.core.MatchResult;
import dev.relism.flash.websocket.WebSocketHandler;

public abstract class AbstractWsRouter {

    public final AbstractWsRouter register(HttpMethod method, String path, WebSocketHandler handler) {
        return addRoute(method, PathUtils.sanitize(path), handler);
    }

    public abstract WebSocketHandler route(Request request);

    protected abstract AbstractWsRouter addRoute(HttpMethod method, String path, WebSocketHandler handler);

    protected static void setPathParams(Request request, MatchResult<WebSocketHandler> result,
                                        String[] allNames, int methodLen) {
        int count = result.paramCount();
        if (count == 0) return;
        String[] names  = new String[count];
        int[]    starts = new int[count];
        int[]    lens   = new int[count];
        for (int i = 0; i < count; i++) {
            names[i]  = allNames[result.keyIdAt(i)];
            starts[i] = result.startAt(i) - methodLen;
            lens[i]   = result.lenAt(i);
        }
        PathParams.inject(request,
            new PathParams(request.getRequestLine().getPath(), names, starts, lens));
    }
}
