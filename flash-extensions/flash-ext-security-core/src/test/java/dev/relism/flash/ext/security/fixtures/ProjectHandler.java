package dev.relism.flash.ext.security.fixtures;

import dev.relism.flash.ext.openapi.ApiOperation;
import dev.relism.flash.ext.security.RolesAllowed;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.GET;

@GET("/projects/{project}")
@ApiOperation(summary = "fixture")
@RolesAllowed(value = "MANAGER", on = "project")
public final class ProjectHandler extends RequestHandler {
    @Override
    public Object handle(Request req, Response res) {
        return SecurityIdentity.current().principal().name();
    }
}
