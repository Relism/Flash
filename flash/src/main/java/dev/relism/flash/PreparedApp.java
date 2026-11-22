package dev.relism.flash;

import dev.relism.flash.http.Exchange;
import dev.relism.flash.routing.Router;

/** An independently compiled application, dispatched inside another app without a network listener. */
public final class PreparedApp implements AutoCloseable {
    private final Router router;
    private final Services services;

    PreparedApp(Router router, Services services) {
        this.router = router;
        this.services = services;
    }

    /** Prepares the response on the existing exchange; the outer application executes its deferred body. */
    public void handle(Exchange exchange) throws Exception {
        exchange.dispatchEmbedded(router);
    }

    /** Call only after requests using this application have drained. */
    @Override public void close() {
        services.close();
    }
}
