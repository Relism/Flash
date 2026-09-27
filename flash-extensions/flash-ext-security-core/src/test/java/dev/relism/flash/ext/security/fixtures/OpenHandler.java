package dev.relism.flash.ext.security.fixtures;

import dev.relism.flash.ext.security.PermitAll;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.GET;

@GET("/open")
@PermitAll
public final class OpenHandler implements Handler {
    @Override
    public Object handle(Request req, Response res) {
        SecurityIdentity identity = SecurityIdentity.current();
        return identity == null ? "anonymous" : identity.principal().name();
    }
}
