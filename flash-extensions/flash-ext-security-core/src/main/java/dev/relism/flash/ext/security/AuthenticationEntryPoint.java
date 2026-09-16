package dev.relism.flash.ext.security;

import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

/** Answers a request that needs a caller and carries no credential at all. */
@FunctionalInterface
public interface AuthenticationEntryPoint {

    Object commence(Request req, Response res) throws Exception;
}
