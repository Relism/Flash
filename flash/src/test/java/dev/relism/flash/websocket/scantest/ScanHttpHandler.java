package dev.relism.flash.websocket.scantest;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.GET;

@GET("/http")
public class ScanHttpHandler extends RequestHandler {

    @Override
    public Object handle(Request request, Response response) {
        return "http";
    }
}
