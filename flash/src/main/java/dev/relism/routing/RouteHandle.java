package dev.relism.routing;

import java.util.function.Consumer;

/**
 * Deferred route registration handle returned by {@code router.get()},
 * {@code router.post()}, {@code app.get()}, {@code app.register()}, etc.
 *
 * <p>The route is <em>not</em> registered until {@link #with} is called.
 * This separates the handler declaration from the middleware declaration,
 * keeping the registration methods clean.
 *
 * <pre>{@code
 * // No middleware
 * app.get("/ping", (req, res) -> "pong").with();
 *
 * // With middleware
 * app.get("/me",    (req, res) -> ClaimsHolder.user().email()).with(oidc.protect());
 * app.get("/admin", (req, res) -> "admin").with(oidc.requireRole("admin"));
 *
 * // Class-based handler — annotations (@Authenticated, @RolesAllowed) are
 * // processed automatically; .with() can add extra middleware on top.
 * app.register(new MyHandler()).with();
 * app.register(new SecuredHandler()).with(logging);
 * }</pre>
 *
 * @param <P> the parent type returned by {@link #with} for further chaining
 *            ({@link dev.relism.extension.FlashApp} or {@link AbstractRouter})
 */
public final class RouteHandle<P> {

    private final P parent;
    private final Consumer<Middleware[]> registerFn;
    private boolean registered = false;

    public RouteHandle(P parent, Consumer<Middleware[]> registerFn) {
        this.parent = parent;
        this.registerFn = registerFn;
    }

    /**
     * Registers the route with the given middlewares and returns the parent
     * for further chaining.
     *
     * <p>Calling without arguments is equivalent to registering with no middleware.
     * When using {@link dev.relism.extension.FlashApp}, calling {@code .with()} is
     * optional for routes that need no middleware — the route is registered
     * automatically before the next operation or at {@code start()}.
     *
     * @param middlewares zero or more middlewares; first element executes outermost
     * @return the parent ({@link dev.relism.extension.FlashApp} or
     *         {@link AbstractRouter}) for further method chaining
     */
    public P with(Middleware... middlewares) {
        if (!registered) {
            registerFn.accept(middlewares);
            registered = true;
        }
        return parent;
    }

    /**
     * Registers the route with no middleware if it has not been registered yet.
     * Called automatically by {@link dev.relism.extension.FlashApp} before each
     * new route registration and at {@code start()}.
     */
    public void ensureRegistered() {
        with();
    }
}
