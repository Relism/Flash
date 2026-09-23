package dev.relism.flash.ext.jackson.json;

import dev.relism.flash.ext.jackson.Codec;
import dev.relism.flash.ext.jackson.JacksonHandler;
import dev.relism.flash.extension.Inject;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.Consumes;

/**
 * A handler that takes a JSON body of one type.
 *
 * <pre>{@code
 * @POST("/users")
 * public final class CreateUser extends JsonHandler<NewUser> {
 *     @Inject private UserService users;
 *
 *     @Override protected Object handle(Request req, Response res, NewUser body) {
 *         return users.create(body);
 *     }
 * }
 * }</pre>
 *
 * <p>The body is read off the request stream, verified against the constraints its type declares,
 * and handed over. A malformed body is a 400, a body that breaks a constraint is a 422, and
 * neither ever reaches the handler. The published OpenAPI document describes the same type.
 */
@Consumes(ContentType.JSON)
public abstract class JsonHandler<B> extends JacksonHandler<B> {

    @Inject private Json json;

    @Override protected final Codec codec() {
        return json;
    }
}
