package dev.relism.flash.http;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/**
 * A handler that names what it takes and what it answers, leaving both conversions to a subclass.
 *
 * <p>The two types are written once, in the signature. {@link #read} turns the request into
 * {@code I}, {@link #handle} answers an {@code O}, {@link #write} turns that into a body. What
 * those two steps are — a codec, a template engine, a form decoder — is nothing this class knows.
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
 * @param <I> what the request is read as; {@link Void} for a handler that reads nothing
 * @param <O> what this handler answers, before {@link #write} turns it into a body
 */
public abstract class TypedHandler<I, O> implements Handler {

    /**
     * The type argument {@code subclass} fixes for {@code declaring}'s parameter at {@code index},
     * or null when it leaves it open.
     *
     * <p>Resolved through the whole chain, so an intermediate base class that passes its own type
     * argument along answers with the type its subclass fixed.
     */
    public static Type typeArgument(Class<?> subclass, Class<?> declaring, int index) {
        Map<TypeVariable<?>, Type> bound = new HashMap<>();

        for (Class<?> current = subclass; current != null && current != Object.class; ) {
            if (!(current.getGenericSuperclass() instanceof ParameterizedType parameterized)) {
                current = current.getSuperclass();
                continue;
            }
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            TypeVariable<?>[] variables = raw.getTypeParameters();

            for (int i = 0; i < variables.length && i < arguments.length; i++) {
                bound.put(variables[i], resolve(arguments[i], bound));
            }
            if (raw == declaring) {
                return index < arguments.length ? resolve(arguments[index], bound) : null;
            }
            current = raw;
        }
        return null;
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bound) {
        return type instanceof TypeVariable<?> variable ? bound.getOrDefault(variable, type) : type;
    }

    /** Reads the request as {@code I}. */
    protected abstract I read(Request request) throws Exception;

    /** Turns {@code O} into what the response carries, the way {@link Handler#handle} means it. */
    protected abstract Object write(Response response, O answer) throws Exception;

    protected abstract O handle(Request request, Response response, I in) throws Exception;

    @Override
    public final Object handle(Request request, Response response) throws Exception {
        return write(response, handle(request, response, read(request)));
    }
}
