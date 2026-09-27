package dev.relism.flash.routing;

import dev.relism.flash.http.Handler;

/**
 * Wraps a handler. Composed once per route when the server starts, so a request runs through a
 * plain chain of handlers: no list is walked and nothing is allocated per request.
 *
 * <pre>{@code
 * Middleware auth = next -> (req, res) -> {
 *     if (req.header("Authorization") == null) throw HttpException.unauthorized();
 *     return next.handle(req, res);
 * };
 * }</pre>
 */
@FunctionalInterface
public interface Middleware {

    Handler wrap(Handler next);
}
