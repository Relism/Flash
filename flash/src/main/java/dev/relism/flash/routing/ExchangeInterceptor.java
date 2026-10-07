package dev.relism.flash.routing;

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

    /** Wraps preparation and execution of one compiled route at boot. */
    Next wrap(Route route, Next exchange);

    /** Observes requests for which no route matched. */
    default Next wrapFallback(Next exchange) {
        return exchange;
    }
}
