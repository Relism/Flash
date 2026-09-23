package dev.relism.flash.ext.jackson.xml;

import dev.relism.flash.ext.jackson.Codec;
import dev.relism.flash.ext.jackson.JacksonHandler;
import dev.relism.flash.extension.Inject;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.Consumes;

/**
 * A handler that takes an XML body of one type.
 *
 * <p>Everything {@code JsonHandler} does, in XML: the body is read off the request stream,
 * verified against its type's constraints, and handed over. Both can live in the same
 * application, on different routes, over the same types.
 */
@Consumes(ContentType.XML)
public abstract class XmlHandler<B> extends JacksonHandler<B> {

    @Inject private Xml xml;

    @Override protected final Codec codec() {
        return xml;
    }
}
