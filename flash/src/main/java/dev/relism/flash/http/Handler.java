package dev.relism.flash.http;

/**
 * What answers a request. A lambda is one; a class implements it, and the app builds it with its
 * services as constructor arguments.
 *
 * <p>The return value is the body: a {@link Response} replaces the whole response, {@code byte[]}
 * and text are written as they are, {@code null} leaves the response as the handler set it.
 */
@FunctionalInterface
public interface Handler {

    Object handle(Request request, Response response) throws Exception;

    /** Runs once at boot, after construction and before the first request: work that belongs off the hot path. */
    default void onInit() {}
}
