package dev.relism.flash.ext.security.fixtures;

import dev.relism.flash.ext.security.PermitAll;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.GET;

@GET("/open")
@PermitAll
public final class OpenHandler extends RequestHandler {
    @Override
    public Object handle(Request req, Response res) {
        SecurityIdentity identity = SecurityIdentity.current();
        return identity == null ? "anonymous" : identity.principal().name();
    }
}
