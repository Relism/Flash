package dev.relism.flash.ext.security.fixtures;

import dev.relism.flash.ext.openapi.ApiOperation;
import dev.relism.flash.ext.security.RolesAllowed;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.GET;

@GET("/projects/{project}")
@ApiOperation(summary = "fixture")
@RolesAllowed(value = "MANAGER", on = "project")
public final class ProjectHandler implements Handler {
    @Override
    public Object handle(Request req, Response res) {
        return SecurityIdentity.current().principal().name();
    }
}
