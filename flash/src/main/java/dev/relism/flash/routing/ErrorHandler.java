package dev.relism.flash.routing;

import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

/** Turns a handler's exception into the response, the way a handler's return value is. */
@FunctionalInterface
public interface ErrorHandler {

    Object handle(Exception failure, Request request, Response response) throws Exception;
}
