package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.PathParams;
import dev.relism.flash.models.Request;
import dev.relism.fpr.core.MatchResult;
import dev.relism.flash.websocket.WebSocketHandler;

public abstract class AbstractWsRouter {

    /** Eagerly validates and compiles this WebSocket route graph before traffic is accepted. */
    public void compile() {}

    public final AbstractWsRouter register(HttpMethod method, String path, WebSocketHandler handler) {
        return addRoute(method, PathUtils.sanitize(path), handler);
    }

    /**
     * Creates a fresh per-connection scratch object for {@link #route}, or {@code null} if this
     * router keeps no reusable per-connection state — see {@link AbstractRouter#newScratch} for
     * the full rationale ({@code EX-06}'s router-half fix), mirrored here for the WebSocket
     * router.
     */
    public Object newScratch() {
        return null;
    }

    public abstract WebSocketHandler route(Request request, Object scratch);

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
