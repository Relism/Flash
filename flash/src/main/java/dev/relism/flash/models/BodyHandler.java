package dev.relism.flash.models;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/**
 * A handler whose request carries a body of a known type.
 *
 * <p>The type is the class's own type argument, so it is written once, in the signature:
 *
 * <pre>{@code
 * @POST("/users")
 * public final class CreateUser extends JsonHandler<NewUser> {
 *     @Override protected Object handle(Request req, Response res, NewUser body) {
 *         return users.create(body);
 *     }
 * }
 * }</pre>
 *
 * <p>Reading the body is the format's job: a subclass such as {@code JsonHandler} implements
 * {@link #body} and declares its media type with
 * {@link dev.relism.flash.routing.Consumes @Consumes}. Documentation tools read both off the
 * class, which is what lets a request body be described without saying its type a second time.
 *
 * @param <B> the body type
 */
public abstract class BodyHandler<B> extends RequestHandler {

    /**
     * The body type a handler class declares, or {@code null} when it leaves it open.
     *
     * <p>Resolved through the whole chain, so an intermediate base class that passes its own type
     * argument along answers with the type its subclass fixed.
     */
    public static Class<?> bodyTypeOf(Class<?> handlerClass) {
        Map<TypeVariable<?>, Type> bound = new HashMap<>();

        for (Class<?> current = handlerClass; current != null && current != Object.class; ) {
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
            if (raw == BodyHandler.class) {
                return resolve(arguments[0], bound) instanceof Class<?> type ? type : null;
            }
            current = raw;
        }
        return null;
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bound) {
        return type instanceof TypeVariable<?> variable ? bound.getOrDefault(variable, type) : type;
    }

    /** This handler's body type, for the reader in {@link #body}. Null only on a handler left generic. */
    @SuppressWarnings("unchecked")
    protected final Class<B> bodyType() {
        return (Class<B>) bodyTypeOf(getClass());
    }

    /** Reads the body in the format this handler speaks. */
    protected abstract B body(Request request) throws Exception;

    protected abstract Object handle(Request request, Response response, B body) throws Exception;

    @Override
    public final Object handle(Request request, Response response) throws Exception {
        return handle(request, response, body(request));
    }
}
