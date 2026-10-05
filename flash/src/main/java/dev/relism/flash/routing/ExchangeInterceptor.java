package dev.relism.flash.routing;

import dev.relism.flash.http.Handler;

/**
 * Infrastructure around a fully handled HTTP exchange. Unlike {@link Middleware}, an interceptor
 * sits outside the application's error boundary, so the value returned by {@code exchange}
 * already reflects the configured error handler.
 *
 * <p>Interceptors are contributed as services and composed once at boot. The first one added is
 * outermost. With none installed, Flash builds the ordinary route directly and adds no request-path
 * branch or wrapper.
 */
@FunctionalInterface
public interface ExchangeInterceptor {

    /** Wraps one compiled route. {@code route} and the returned handler are retained for its lifetime. */
    Handler wrap(Route route, Handler exchange);

    /** Wraps requests for which no route matched. */
    default Handler wrapFallback(Handler exchange) {
        return exchange;
    }
}
