package dev.relism.flash.ext.jackson.xml;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.relism.flash.ext.jackson.JacksonDocs;
import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.http.ContentType;

/**
 * XML for an application: the {@link Xml} codec.
 *
 * <pre>{@code
 * app.install(new XmlModule());
 * }</pre>
 *
 * <p>Install it beside {@code JsonModule} when an application speaks both: each provides its
 * own codec, and a route picks one by the handler it extends.
 */
public class XmlModule implements Module {

    private final XmlMapper mapper;

    public XmlModule() {
        this(XmlMapper.builder().findAndAddModules().build());
    }

    public XmlModule(XmlMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void install(App app) {
        app.add(Xml.class, new Xml(mapper)).install(JacksonDocs.module());
    }
}
