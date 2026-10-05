package dev.relism.flash.ext.protobuf;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.StringValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProtobufDocsTest {

    @Test
    void generatedMessagesAreDescribedFromTheirDescriptors() {
        ProtobufDocs.Schemas docs = new ProtobufDocs.Schemas();

        assertEquals(Map.of("$ref", "#/components/schemas/google.protobuf.FileDescriptorProto"),
                docs.schema(FileDescriptorProto.class));

        Map<String, Object> schemas = map(docs.componentContributions().get("schemas"));
        Map<String, Object> file = map(schemas.get("google.protobuf.FileDescriptorProto"));
        Map<String, Object> properties = map(file.get("properties"));
        assertEquals(Map.of("type", "string"), properties.get("name"));
        assertEquals("array", map(properties.get("dependency")).get("type"));
        assertNotNull(schemas.get("google.protobuf.DescriptorProto"));
    }

    @Test
    void wellKnownTypesUseTheirOpenApiShape() {
        assertEquals(Map.of("type", "string", "nullable", true),
                new ProtobufDocs.Schemas().schema(StringValue.class));
    }

    @Test
    void aReferenceKeepsItsSiblingsByBeingWrapped() {
        Map<String, Object> ref = Map.of("$ref", "#/components/schemas/x.Y");

        assertEquals(Map.of("allOf", java.util.List.of(ref), "deprecated", true),
                ProtobufDocs.Schemas.with(ref, "deprecated", true));
        assertEquals(Map.of("type", "string", "deprecated", true),
                ProtobufDocs.Schemas.with(Map.of("type", "string"), "deprecated", true));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
