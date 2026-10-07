package dev.relism.flash.scanbadsse;

import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.Sse;

@Sse("/wrong")
public final class NotSse extends RequestHandler {
    @Override protected Object handle(Request request, Response response) { return null; }
}
