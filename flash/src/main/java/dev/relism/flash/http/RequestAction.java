package dev.relism.flash.http;

/** A request/response lambda accepted by {@code App}. */
@FunctionalInterface
public interface RequestAction {
    Object handle(Request request, Response response) throws Exception;
}
