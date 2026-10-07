package dev.relism.flash.http;

import dev.relism.flash.Bound;
import dev.relism.flash.Services;

/**
 * The single root of every route handler.
 *
 * <p>An application creates one handler at boot and may use it concurrently. {@link #onInit()}
 * therefore prepares immutable handler state; request state belongs to {@link Exchange}. Ordinary
 * request/answer handlers extend {@link RequestHandler}; protocols that own an exchange extend
 * {@link DuplexHandler}.
 */
public abstract sealed class Handler implements Bound permits RequestHandler, DuplexHandler {

    private Services services;

    /** Called by the app once, after construction and before the first request. */
    public final void initialize(Services services) {
        if (this.services != null) {
            if (this.services != services) throw new IllegalStateException("handler already belongs to another app");
            return;
        }
        this.services = services;
        onInit();
    }

    @Override
    public final void bind(Services services) {
        initialize(services);
    }

    /** The services of the application that owns this handler. Available from {@link #onInit()}. */
    protected final Services services() {
        return services;
    }

    /** Boot-only validation and preparation for subclasses. */
    protected void onInit() {}

    /**
     * Prepares one exchange without sending bytes. Middleware surrounds this phase, so it can
     * still change response status and headers after its {@code next} returns.
     */
    public final void prepare(Exchange exchange) throws Exception {
        prepareExchange(exchange);
    }

    protected abstract void prepareExchange(Exchange exchange) throws Exception;
}
