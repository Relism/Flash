package dev.relism.flash.ext.jackson.xml;

import dev.relism.flash.ext.serde.CodecRequestHandler;

/**
 * A handler that reads XML and answers XML.
 *
 * <p>Everything {@code JsonHandler} does, in XML. {@link Void} as the first type reads nothing —
 * which is how a route that only answers XML is written — and answering another format is
 * {@link CodecRequestHandler} with both codecs named.
 */
public abstract class XmlHandler<I, O> extends CodecRequestHandler<Xml, I, Xml, O> {}
