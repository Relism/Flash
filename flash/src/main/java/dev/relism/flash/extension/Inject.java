package dev.relism.flash.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A service this handler needs, filled in once when the handler is bound.
 *
 * <pre>{@code
 * public final class ListUsers extends RequestHandler {
 *     @Inject private UserService users;
 *
 *     @Override public Object handle(Request req, Response res) { return users.findAll(); }
 * }
 * }</pre>
 *
 * <p>This is how a handler takes a service. {@code onInit} stays for what has to be computed at
 * boot, or for a service that may not be there ({@code find}).
 *
 * <h3>What it does, exactly</h3>
 * <ul>
 *   <li>Filled inside {@code bind}, <b>before</b> {@code onInit}, once per handler instance when
 *       the route is registered. Never per request: the request path reads a field.</li>
 *   <li>Every annotated field of the handler and of its bases up to {@code RequestHandler} is
 *       filled, {@code private} included.</li>
 *   <li>The service is looked up by the field's <b>declared type, exactly</b> — not a supertype,
 *       not a generic parameter. A type nothing provides fails the boot, naming the field.</li>
 *   <li>A {@code static} field is refused: it would be shared by every handler. A {@code final}
 *       one is refused too: it is written after construction, and a reader may have folded it.</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Inject {}
