package dev.relism.flash.ext.jackson.xml;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.relism.flash.ext.jackson.JacksonCodec;
import dev.relism.flash.http.ContentType;

/**
 * XML in and out, checked against the body type's own constraints.
 *
 * <p>The same databind model as every other Jackson format: a type is annotated once and can be
 * read as XML here and as JSON elsewhere in the same application. XML's own concerns — a root
 * element name, an attribute rather than an element, how a list is wrapped — are Jackson's
 * {@code @JacksonXml*} annotations on the type.
 */
public final class Xml extends JacksonCodec {

    public Xml(XmlMapper mapper) {
        super(mapper, ContentType.XML);
    }
}
