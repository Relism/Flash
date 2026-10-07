package dev.relism.flash.websocket.scantest;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.GET;

@GET("/http")
public class ScanHttpHandler extends RequestHandler {

    @Override
    protected Object handle(Request request, Response response) {
        return "http";
    }
}
