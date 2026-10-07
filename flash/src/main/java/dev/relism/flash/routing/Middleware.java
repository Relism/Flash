package dev.relism.flash.routing;

import dev.relism.flash.http.Exchange;

/**
 * Runs around handler preparation. Composed once per route, with no per-request allocation.
 *
 * <pre>{@code
 * Middleware auth = (exchange, next) -> {
 *     if (exchange.request().header("Authorization") == null) throw HttpException.unauthorized();
 *     next.handle(exchange);
 * };
 * }</pre>
 */
@FunctionalInterface
public interface Middleware {
    void handle(Exchange exchange, Next next) throws Exception;
}
