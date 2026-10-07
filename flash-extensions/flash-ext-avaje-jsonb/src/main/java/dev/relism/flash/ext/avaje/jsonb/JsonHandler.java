package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.ext.serde.CodecRequestHandler;

/**
 * A handler that reads JSON and answers JSON.
 *
 * <pre>{@code
 * @POST("/users")
 * public final class CreateUser extends JsonHandler<NewUser, User> {
 *     private final UserService users;
 *
 *     public CreateUser(UserService users) { this.users = users; }
 *
 *     @Override protected User handle(Request req, Response res, NewUser body) {
 *         return users.create(body);
 *     }
 * }
 * }</pre>
 *
 * <p>Both types are resolved at boot, so one without a generated adapter fails there. {@link Void}
 * as the first type reads nothing.
 */
public abstract class JsonHandler<I, O> extends CodecRequestHandler<Json, I, Json, O> {}
