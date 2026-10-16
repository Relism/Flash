package dev.relism.flash.scanany;

import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Response;

@Mounted("/scanned/**")
public final class Echo extends RequestHandler {
    @Override
    protected Object handle(Request request, Response response) {
        return request.requestLine().getMethodName() + " " + request.param("**");
    }
}
