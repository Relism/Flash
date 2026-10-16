package dev.relism.flash.ext.webdav;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The XML of WebDAV: request bodies read into a small tree with the JDK's StAX parser, no DTD and no
 * external entity ever resolved; multistatus answers written as they are produced.
 */
final class Xml {

    static final String DAV = "DAV:";
    /** The largest PROPFIND, PROPPATCH or LOCK body read. */
    static final int MAX_BODY = 1024 * 1024;

    private static final XMLInputFactory FACTORY = factory();

    private Xml() {}

    /**
     * One element. {@code xml} is its content written back out with every namespace declared where it
     * is used, so it stands on its own wherever it is put.
     */
    record Element(String ns, String name, List<Element> children, String xml) {

        QName qname() {
            return new QName(ns, name);
        }

        boolean is(String ns, String name) {
            return this.ns.equals(ns) && this.name.equals(name);
        }

        Element child(String ns, String name) {
            for (Element child : children) if (child.is(ns, name)) return child;
            return null;
        }
    }

    /** The request body as a tree, or {@code null} when there is none. */
    static Element read(Request request) throws IOException {
        if (request.body().isEmpty()) return null;
        byte[] body;
        try (InputStream in = request.body().stream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) throw new HttpException(413, "WebDAV bodies are limited to " + MAX_BODY + " bytes");
        if (body.length == 0) return null;
        try {
            XMLStreamReader reader = FACTORY.createXMLStreamReader(new ByteArrayInputStream(body));
            while (reader.next() != XMLStreamConstants.START_ELEMENT) {}
            Element root = element(reader);
            while (reader.hasNext()) reader.next(); // the rest must still be well-formed
            return root;
        } catch (XMLStreamException | IllegalArgumentException malformed) {
            throw HttpException.badRequest("malformed XML body: " + malformed.getMessage());
        }
    }

    private static Element element(XMLStreamReader reader) throws XMLStreamException {
        String ns = namespace(reader.getNamespaceURI());
        String name = reader.getLocalName();
        List<Element> children = new ArrayList<>();
        StringBuilder xml = new StringBuilder();
        while (true) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    StringBuilder start = new StringBuilder("<");
                    String childNs = namespace(reader.getNamespaceURI());
                    start.append(childNs.isEmpty() ? "" : "n:").append(reader.getLocalName());
                    if (!childNs.isEmpty()) start.append(" xmlns:n=\"").append(escape(childNs)).append('"');
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        String attributeNs = namespace(reader.getAttributeNamespace(i));
                        String prefix = attributeNs.isEmpty() ? "" : attributeNs.equals(XMLConstants.XML_NS_URI) ? "xml" : "a" + i;
                        if (!prefix.isEmpty() && !prefix.equals("xml")) start.append(" xmlns:").append(prefix).append("=\"").append(escape(attributeNs)).append('"');
                        start.append(' ').append(prefix.isEmpty() ? "" : prefix + ":").append(reader.getAttributeLocalName(i))
                                .append("=\"").append(escape(reader.getAttributeValue(i))).append('"');
                    }
                    Element child = element(reader);
                    children.add(child);
                    String tag = (childNs.isEmpty() ? "" : "n:") + child.name;
                    xml.append(start).append('>').append(child.xml).append("</").append(tag).append('>');
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        xml.append(escape(reader.getText()));
                case XMLStreamConstants.END_ELEMENT -> {
                    return new Element(ns, name, List.copyOf(children), xml.toString());
                }
                default -> {} // comments, processing instructions
            }
        }
    }

    private static String namespace(String uri) {
        return uri == null ? "" : uri;
    }

    static String escape(String text) {
        StringBuilder escaped = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String entity = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\r' -> "&#13;";
                default -> null;
            };
            if (entity == null) {
                if (escaped != null) escaped.append(c);
                continue;
            }
            if (escaped == null) escaped = new StringBuilder(text.length() + 16).append(text, 0, i);
            escaped.append(entity);
        }
        return escaped == null ? text : escaped.toString();
    }

    /** {@code {ns}name}, the form names are kept in; {@code {}name} for no namespace. */
    static String clark(QName name) {
        return "{" + name.getNamespaceURI() + "}" + name.getLocalPart();
    }

    /** The name a {@link #clark} string stands for; {@code QName.valueOf} refuses an empty namespace. */
    static QName qname(String clark) {
        int close = clark.indexOf('}');
        return new QName(clark.substring(1, close), clark.substring(close + 1));
    }

    /** An element of a namespace that may be {@code DAV:}, empty, or anything else, opened or empty. */
    static String tag(QName qname, boolean open) {
        String ns = qname.getNamespaceURI();
        String prefix = prefix(ns);
        return "<" + prefix + qname.getLocalPart() + (prefix.equals("x:") ? " xmlns:x=\"" + escape(ns) + "\"" : "") + (open ? ">" : "/>");
    }

    /** {@code D:} for {@code DAV:}, nothing for no namespace, {@code x:} declared on the element for any other. */
    private static String prefix(String ns) {
        return ns.equals(DAV) ? "D:" : ns.isEmpty() ? "" : "x:";
    }

    /** UTF-8 text written straight into one reused buffer and passed on in batches. */
    static final class Writer {
        private static final int BATCH = 8 * 1024;
        private final OutputStream out;
        private final ByteWriter buffer = new ByteWriter(BATCH);

        Writer(OutputStream out) {
            this.out = out;
        }

        Writer raw(String text) throws IOException {
            if (ascii(text)) buffer.writeAscii(text);
            else buffer.writeBytes(text.getBytes(StandardCharsets.UTF_8));
            return batched();
        }

        Writer text(String text) throws IOException {
            return raw(escape(text));
        }

        Writer number(long value) throws IOException {
            buffer.writeDecimal(value);
            return batched();
        }

        /** The element {@link Xml#tag} gives, written without building it. */
        Writer tag(QName qname, boolean open) throws IOException {
            String ns = qname.getNamespaceURI();
            String prefix = prefix(ns);
            raw("<").raw(prefix).raw(qname.getLocalPart());
            if (prefix.equals("x:")) raw(" xmlns:x=\"").text(ns).raw("\"");
            return raw(open ? ">" : "/>");
        }

        Writer end(QName qname) throws IOException {
            return raw("</").raw(prefix(qname.getNamespaceURI())).raw(qname.getLocalPart()).raw(">");
        }

        private Writer batched() throws IOException {
            if (buffer.length() >= BATCH) flush();
            return this;
        }

        private static boolean ascii(String text) {
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= 0x80) return false;
            return true;
        }

        void flush() throws IOException {
            out.write(buffer.array(), 0, buffer.length());
            buffer.reset();
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }
}
