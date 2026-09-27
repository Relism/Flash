package dev.relism.flash.ext.serde;

import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http.TypedHandler;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * A handler that names a codec and a type for each end.
 *
 * <pre>{@code
 * @POST("/orders")
 * public final class PlaceOrder extends CodecHandler<Xml, Order, Json, Receipt> {
 *     private final Orders orders;
 *
 *     public PlaceOrder(Orders orders) { this.orders = orders; }
 *
 *     @Override protected Receipt handle(Request req, Response res, Order order) { return orders.place(order); }
 * }
 * }</pre>
 *
 * <p>Any pair works: the two ends are independent, and a handler that reads XML may answer JSON.
 * A format module names the symmetric case for its own users — {@code JsonHandler<I, O>} is this
 * class with both ends fixed. {@link Void} as {@code I} means the handler reads nothing, and its
 * request codec is then never asked for anything.
 *
 * <p>Both codecs are looked up once at boot by the classes in the signature, and each is asked to
 * {@link Codec#prepare prepare} the type it will carry, unless that end carries nothing it has to
 * encode. A codec the app does not have fails the boot naming it. Services come through the
 * constructor like any handler's, and {@code onInit} is free for the handler's own work.
 *
 * @param <CI> the codec the request is read with
 * @param <I>  what the request is read as
 * @param <CO> the codec the answer is written with
 * @param <O>  what this handler answers
 */
public abstract class CodecHandler<CI extends Codec, I, CO extends Codec, O> extends TypedHandler<I, O> implements Bound {

    private Codec in;
    private Codec out;
    private Type inType;

    @Override
    public final void bind(Services services) {
        inType = argument(1);
        in = inType == Void.class ? null : services.get(codec(0));
        out = services.get(codec(2));
        if (in != null) in.prepare(inType);
        Type outType = typeArgument(getClass(), CodecHandler.class, 3);
        if (!(outType instanceof TypeVariable<?>) && !passedThrough(outType)) out.prepare(outType);
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

    /** What {@link #write} hands back untouched, so the codec never has to know it. */
    private static boolean passedThrough(Type type) {
        return type == Void.class || type == byte[].class || type instanceof Class<?> c
                && (Response.class.isAssignableFrom(c) || CharSequence.class.isAssignableFrom(c));
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
