package dev.relism.flash.ext.jackson.xml;

import dev.relism.flash.ext.serde.CodecHandler;

/**
 * A handler that reads XML and answers XML.
 *
 * <p>Everything {@code JsonHandler} does, in XML. {@link Void} as the first type reads nothing —
 * which is how a route that only answers XML is written — and answering another format is
 * {@link CodecHandler} with both codecs named, as in {@code CodecHandler<Xml, Order, Json, Receipt>}.
 */
public abstract class XmlHandler<I, O> extends CodecHandler<Xml, I, Xml, O> {}
