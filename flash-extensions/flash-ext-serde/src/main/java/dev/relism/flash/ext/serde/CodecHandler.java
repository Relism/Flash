package dev.relism.flash.ext.serde;

import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.TypedHandler;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * A handler that names a codec and a type for each end.
 *
 * <pre>{@code
 * @POST("/orders")
 * public final class PlaceOrder extends CodecHandler<Xml, Order, Json, Receipt> {
 *     @Override protected Receipt handle(Request req, Response res, Order order) { … }
 * }
 * }</pre>
 *
 * <p>Any pair works: the two ends are independent, and a handler that reads XML may answer JSON.
 * A format module names the symmetric case for its own users — {@code JsonHandler<I, O>} is this
 * class with both ends fixed. {@link Void} as {@code I} means the handler reads nothing, and its
 * request codec is then never asked for anything.
 *
 * <p>Both codecs are resolved once at boot, from the context, by the classes in the signature: a
 * codec nothing provides fails the boot naming it.
 *
 * @param <CI> the codec the request is read with
 * @param <I>  what the request is read as
 * @param <CO> the codec the answer is written with
 * @param <O>  what this handler answers
 */
public abstract class CodecHandler<CI extends Codec, I, CO extends Codec, O> extends TypedHandler<I, O> {

    private Codec in;
    private Codec out;
    private Type inType;

    @Override
    protected void onInit() {
        inType = argument(1);
        in = inType == Void.class ? null : require(codec(0));
        out = require(codec(2));
    }

    @Override
    protected final I read(Request request) throws Exception {
        return in == null ? null : in.read(request, inType);
    }

    /** Pass-through for what is already a body: {@code null}, a {@link Response}, bytes or text. */
    @Override
    protected final Object write(Response response, O answer) throws Exception {
        if (answer == null || answer instanceof Response || answer instanceof byte[] || answer instanceof CharSequence)
            return answer;
        return out.write(response, answer);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Codec> codec(int index) {
        if (argument(index) instanceof Class<?> type && Codec.class.isAssignableFrom(type))
            return (Class<? extends Codec>) type;
        throw new IllegalStateException(getClass().getSimpleName()
                + " leaves the " + (index == 0 ? "request" : "response") + " codec open — name one in its signature");
    }

    /** A generic type is fine here; one the handler never fixed is not. */
    private Type argument(int index) {
        Type argument = typeArgument(getClass(), CodecHandler.class, index);
        if (argument == null || argument instanceof TypeVariable<?>)
            throw new IllegalStateException(getClass().getSimpleName()
                    + " leaves its type arguments open — write it as Handler<YourRequest, YourAnswer>");
        return argument;
    }
}
