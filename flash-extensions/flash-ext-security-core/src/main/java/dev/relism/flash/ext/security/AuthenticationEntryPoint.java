package dev.relism.flash.ext.security;

import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

/** Answers a request that needs a caller and carries no credential at all. */
@FunctionalInterface
public interface AuthenticationEntryPoint {

    Object commence(Request req, Response res) throws Exception;
}
