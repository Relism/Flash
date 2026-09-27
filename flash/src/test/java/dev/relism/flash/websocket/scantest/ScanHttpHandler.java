package dev.relism.flash.websocket.scantest;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.GET;

@GET("/http")
public class ScanHttpHandler implements Handler {

    @Override
    public Object handle(Request request, Response response) {
        return "http";
    }
}
