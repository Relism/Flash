package dev.relism.flash.ext.limiter;

import dev.relism.flash.http.Request;

/** Who a request counts against: an address, a user, an API key. Named with {@link LimiterModule#resolver}. */
@FunctionalInterface
public interface KeyResolver {
    String resolve(Request req);
}
