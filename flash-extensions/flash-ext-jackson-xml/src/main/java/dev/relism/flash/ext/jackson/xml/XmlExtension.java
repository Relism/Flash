package dev.relism.flash.ext.jackson.xml;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.relism.flash.ext.serde.CodecDocs;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.http.ContentType;

/**
 * XML for an application: the {@link Xml} codec and the middleware that serializes what a handler
 * returns.
 *
 * <pre>{@code
 * app.install(new XmlExtension());
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


    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        Xml xml = new Xml(mapper);
        ctx.provide(Xml.class, xml);
        CodecDocs.register(ctx);
        xml.bindValidation(ctx);
    }
}
