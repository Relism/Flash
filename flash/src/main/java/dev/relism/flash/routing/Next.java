package dev.relism.flash.routing;

import dev.relism.flash.http.Exchange;

/** One precompiled step in an exchange pipeline. */
@FunctionalInterface
public interface Next {
    void handle(Exchange exchange) throws Exception;
}
