package dev.relism.flash.ext.jackson;

import dev.relism.flash.models.BodyHandler;
import dev.relism.flash.models.Request;

/**
 * A handler whose body one Jackson format parses and whose constraints are checked before it
 * arrives.
 *
 * <p>Format modules extend this and name their codec — {@code JsonHandler}, {@code XmlHandler}.
 * An application extends those, never this one.
 *
 * @param <B> the body type, which is also what the published OpenAPI document describes
 */
public abstract class JacksonHandler<B> extends BodyHandler<B> {

    private final Class<B> type = bodyType();

    protected JacksonHandler() {
        if (type == null) {
            throw new IllegalStateException(getClass().getSimpleName()
                    + " extends a body handler without naming its body type — write it as Handler<YourBody>");
        }
    }

    /** The format this handler speaks. Injected by the subclass, resolved once at boot. */
    protected abstract Codec codec();

    @Override
    protected final B body(Request request) throws Exception {
        return codec().body(request, type);
    }
}
