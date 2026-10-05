package dev.relism.flash.ext.protobuf;

import com.google.protobuf.ExtensionRegistryLite;
import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.serde.CodecDocs;

/** Installs binary Protocol Buffer bodies through Google's protobuf-java runtime. */
public final class ProtobufModule implements Module {

    private final Protobuf protobuf;

    public ProtobufModule() {
        this(new Protobuf());
    }

    public ProtobufModule(ExtensionRegistryLite extensions) {
        this(new Protobuf(extensions));
    }

    public ProtobufModule(Protobuf protobuf) {
        this.protobuf = protobuf;
    }

    @Override
    public void install(App app) {
        app.add(Protobuf.class, protobuf).install(CodecDocs.module(), ProtobufDocs.module());
    }
}
