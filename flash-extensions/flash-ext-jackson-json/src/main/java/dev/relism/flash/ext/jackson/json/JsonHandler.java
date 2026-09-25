package dev.relism.flash.ext.jackson.json;

import dev.relism.flash.ext.serde.CodecHandler;

/**
 * A handler that reads JSON and answers JSON.
 *
 * <pre>{@code
 * @POST("/users")
 * public final class CreateUser extends JsonHandler<NewUser, User> {
 *     @Inject private UserService users;
 *
 *     @Override protected User handle(Request req, Response res, NewUser body) {
 *         return users.create(body);
 *     }
 * }
 * }</pre>
 *
 * <p>The body is read off the request stream, verified against the constraints its type declares,
 * and handed over. A malformed body is a 400, a body that breaks a constraint is a 422, and neither
 * ever reaches the handler. The published document describes both types.
 *
 * <p>{@link Void} as the first type reads nothing, and a pair of formats that differ is
 * {@link CodecHandler} with both named: {@code CodecHandler<Xml, Order, Json, Receipt>}.
 */
public abstract class JsonHandler<I, O> extends CodecHandler<Json, I, Json, O> {}
