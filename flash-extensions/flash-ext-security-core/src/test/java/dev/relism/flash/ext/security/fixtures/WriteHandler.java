package dev.relism.flash.ext.security.fixtures;

import dev.relism.flash.ext.openapi.ApiOperation;
import dev.relism.flash.ext.security.ScopesAllowed;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.POST;

@POST("/write")
@ApiOperation(summary = "fixture")
@ScopesAllowed("write")
public final class WriteHandler extends RequestHandler {
    @Override
    public Object handle(Request req, Response res) {
        return "written";
    }
}
