package dev.relism.flash.models;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.Inject;
import dev.relism.flash.routing.Route;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;

/**
 * Base class for class-based route handlers.
 *
 * <p>Declare the route with {@link Route @Route} or a shorthand such as
 * {@code @GET("/path")}, then register via {@link FlashApp#scan}.
 * For ad-hoc routes use the fluent API ({@code app.get(path, handler)} or with middleware).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Instantiation — no-arg constructor (for scan) or manual {@code new Handler(...)}</li>
 *   <li>{@link #bind} — called once by the framework at {@code start()}, injects the
 *       {@link FlashContext} and invokes {@link #onInit()}</li>
 *   <li>{@link #handle} — called on every matching request (hot-path, zero-alloc)</li>
 * </ol>
 *
 * <h3>Service access</h3>
 * Annotate a field with {@link Inject} and it is filled at boot, or override {@link #onInit()}
 * to cache services from the {@link FlashContext} by hand. Either way the hot path
 * ({@code handle}) reads a field and never the context.
 *
 * <pre>{@code
 * @GET("/users")
 * public class UserHandler extends RequestHandler {
 *     private UserService users;
 *
 *     @Override protected void onInit() {
 *         users = require(UserService.class);
 *     }
 *
 *     @Override public Object handle(Request req, Response res) {
 *         return users.findAll();
 *     }
 * }
 * }</pre>
 */
public abstract class RequestHandler {

    private FlashContext ctx;

    /**
     * Called once by the framework after instantiation, before the first request.
     * Injects the {@link FlashContext} and triggers {@link #onInit()}.
     *
     * <p><b>Infrastructure method</b> — do not call from user code.
     * Use {@link FlashApp#scan} instead.
     */
    public final void bind(FlashContext ctx) {
        this.ctx = ctx;
        inject();
        onInit();
    }

    /** Fills every {@link Inject} field, this class's and its bases', before {@link #onInit}. */
    private void inject() {
        for (Class<?> type = getClass(); type != null && type != RequestHandler.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Inject.class)) continue;
                String where = type.getSimpleName() + "." + field.getName();

                // A static field would be shared by every handler, and a final one may already have
                // been folded into the code that reads it. Both are refused rather than surprising.
                if (Modifier.isStatic(field.getModifiers()))
                    throw new IllegalStateException(where + " is static: an injected field belongs to the handler");
                if (Modifier.isFinal(field.getModifiers()))
                    throw new IllegalStateException(where + " is final: an injected field is written after construction");

                Object service;
                try {
                    service = ctx.require(field.getType());
                } catch (RuntimeException missing) {
                    throw new IllegalStateException(where + " asks for " + field.getType().getSimpleName()
                            + ", which nothing provides", missing);
                }
                try {
                    field.setAccessible(true);
                    field.set(this, service);
                } catch (ReflectiveOperationException | RuntimeException unreachable) {
                    throw new IllegalStateException("Could not write " + where, unreachable);
                }
            }
        }
    }

    /**
     * Override to cache services at boot time. Called once after {@link #bind},
     * before any request reaches this handler.
     *
     * <p>Use {@link #require} and {@link #find} to retrieve services from the
     * {@link FlashContext}. Cache them in private fields so the hot-path
     * ({@link #handle}) has zero lookup overhead.
     *
     * <p><b>Important:</b> if your class extends another handler base (e.g.
     * {@code JsonHandler}), call {@code super.onInit()} first so the parent
     * can initialise its own services.
     *
     * <pre>{@code
     * @Override protected void onInit() {
     *     super.onInit();
     *     myService = require(MyService.class);
     * }
     * }</pre>
     */
    protected void onInit() {}

    /**
     * Retrieves a required service from the {@link FlashContext}.
     * Throws {@link IllegalStateException} if the service is not registered or
     * this handler has not been bound yet.
     *
     * <p>Typically called inside {@link #onInit()} to cache the result.
     *
     * @param type the service class
     * @param <T>  the service type
     * @return the service instance, never null
     */
    protected <T> T require(Class<T> type) {
        checkBound();
        return ctx.require(type);
    }

    /**
     * Looks up an optional service from the {@link FlashContext}.
     *
     * @param type the service class
     * @param <T>  the service type
     * @return the service, or empty if not registered
     */
    protected <T> Optional<T> find(Class<T> type) {
        checkBound();
        return ctx.find(type);
    }

    /**
     * Looks up an optional service from the {@link FlashContext}.
     * Identical to {@link #find} — prefer this name for expressive call sites
     * ({@code optional(MyService.class).ifPresent(...)}).
     *
     * @param type the service class
     * @param <T>  the service type
     * @return the service, or empty if not registered
     */
    protected <T> Optional<T> optional(Class<T> type) {
        checkBound();
        return ctx.optional(type);
    }

    private void checkBound() {
        if (ctx == null)
            throw new IllegalStateException(
                    getClass().getSimpleName() + " has not been bound to a FlashContext — " +
                    "register via FlashApp.scan(), not directly on the router");
    }

    /**
     * Handles an incoming request. The return value determines the response body:
     * return a {@link Response} to replace the whole response, any other non-null value
     * to set it as the body, or {@code null} to leave the response as-is.
     */
    public abstract Object handle(Request request, Response response) throws Exception;
}
