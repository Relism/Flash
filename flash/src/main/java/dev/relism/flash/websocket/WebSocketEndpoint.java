package dev.relism.flash.websocket;

import dev.relism.flash.extension.FlashContext;

import java.util.Optional;

/**
 * Base class for class-based WebSocket endpoints registered via {@code @Ws} or
 * {@code app.ws(path, endpoint)}.
 *
 * Lifecycle: bind() once at boot → onOpen / onMessage* / onClose | onError per connection.
 * No relation to {@link dev.relism.flash.models.RequestHandler} — HTTP and WS lifecycles
 * are fully separate.
 */
public abstract class WebSocketEndpoint implements WebSocketHandler {

    private FlashContext ctx;

    /** Called once by framework at boot. Do not call from user code. */
    public final void bind(FlashContext ctx) {
        this.ctx = ctx;
        onInit();
    }

    /** Override to resolve and cache services before first connection. */
    protected void onInit() {}

    protected <T> T require(Class<T> type) {
        checkBound();
        return ctx.require(type);
    }

    protected <T> Optional<T> find(Class<T> type) {
        checkBound();
        return ctx.find(type);
    }

    private void checkBound() {
        if (ctx == null) throw new IllegalStateException(
            getClass().getSimpleName() + " not bound — register via app.scan() or app.ws()");
    }
}
