package dev.relism.flash.ext.protobuf;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.StringValue;
import dev.relism.flash.App;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.POST;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@SuppressWarnings("unused")
class ProtobufDocumentTest {

    @POST("/descriptors")
    public static final class Describe extends ProtobufHandler<StringValue, FileDescriptorProto> {
        @Override
        protected FileDescriptorProto handle(Request request, Response response, StringValue body) {
            return FileDescriptorProto.newBuilder().setName(body.getValue()).build();
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new ProtobufModule(), new OpenApiModule("/openapi", "Protobuf", "1.0.0"))
            .scan("dev.relism.flash.ext.protobuf"));

    @Test
    void mediaTypeAndDescriptorSchemaArePublishedWithoutGeneratedFields() {
        app.get("/openapi.json")
                .expectStatus(200)
                .expectBodyContains("\"application/protobuf\"")
                .expectBodyContains("#/components/schemas/google.protobuf.FileDescriptorProto");
    }
}
