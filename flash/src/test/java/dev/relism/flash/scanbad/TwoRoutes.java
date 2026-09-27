package dev.relism.flash.scanbad;

import dev.relism.flash.http.Handler;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.GET;
import dev.relism.flash.routing.POST;

@GET("/a")
@POST("/b")
public class TwoRoutes implements Handler {
    @Override public Object handle(Request request, Response response) { return "never"; }
}
