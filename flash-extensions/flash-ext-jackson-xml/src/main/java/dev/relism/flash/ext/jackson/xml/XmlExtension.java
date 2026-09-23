package dev.relism.flash.ext.jackson.xml;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.relism.flash.ext.jackson.Marshalling;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.Middleware;

/**
 * XML for an application: the {@link Xml} codec and the middleware that serializes what a handler
 * returns.
 *
 * <pre>{@code
 * XmlExtension xml = new XmlExtension();
 * app.install(xml).use(xml.auto());
 * }</pre>
 *
 * <p>Install it beside {@code JsonExtension} when an application speaks both: each provides its
 * own codec, and a route picks one by the handler it extends.
 */
public class XmlExtension implements FlashExtension {

    private final XmlMapper mapper;

    public XmlExtension() {
        this(XmlMapper.builder().findAndAddModules().build());
    }

    public XmlExtension(XmlMapper mapper) {
        this.mapper = mapper;
    }

    /** Serializes what a handler returns, unless it already returned a response, bytes or text. */
    public Middleware auto() {
        return Marshalling.of(mapper, ContentType.XML);
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.provide(Xml.class, new Xml(mapper));
    }
}
