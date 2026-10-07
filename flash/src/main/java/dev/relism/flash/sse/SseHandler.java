package dev.relism.flash.sse;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.DuplexHandler;
import dev.relism.flash.http.PreEncodedHeader;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

/** A bare server-sent event stream. */
public abstract class SseHandler extends DuplexHandler {
    private static final PreEncodedHeader NO_CACHE = new PreEncodedHeader("Cache-Control", "no-cache");

    protected abstract void handle(Request request, SseStream stream) throws Exception;

    @Override
    protected final void prepare(Request request, Response response) {
        response.type(ContentType.TEXT_EVENT_STREAM).header(NO_CACHE);
    }

    @Override
    protected final void handle(Request request, Response response, Duplex duplex) throws Exception {
        duplex.flush();
        SseStream stream = new SseStream(duplex);
        try {
            handle(request, stream);
        } finally {
            stream.close();
        }
    }
}
