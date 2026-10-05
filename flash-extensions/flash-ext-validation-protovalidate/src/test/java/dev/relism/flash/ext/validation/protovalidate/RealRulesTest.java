package dev.relism.flash.ext.validation.protovalidate;

import build.buf.validate.AnyRules;
import build.buf.validate.BytesRules;
import build.buf.validate.DurationRules;
import build.buf.validate.EnumRules;
import build.buf.validate.FieldRules;
import build.buf.validate.Int32Rules;
import build.buf.validate.MapRules;
import build.buf.validate.MessageOneofRule;
import build.buf.validate.MessageRules;
import build.buf.validate.RepeatedRules;
import build.buf.validate.Rule;
import build.buf.validate.StringRules;
import build.buf.validate.UInt32Rules;
import build.buf.validate.ValidateProto;
import com.google.protobuf.AnyProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.DurationProto;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.StringValue;
import dev.relism.flash.ext.validation.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Messages whose descriptors really carry {@code buf.validate} rules, built without protoc. */
class RealRulesTest {

    private static final FileDescriptor FILE = file();
    private static final Descriptor USER = FILE.findMessageTypeByName("User");
    private static final Descriptor EXTRAS = FILE.findMessageTypeByName("Extras");

    @Test
    void theRealEngineEnforcesTheRulesAndNamesTheField() {
        ValidationException failure = assertThrows(ValidationException.class,
                () -> new ProtovalidateValidation().verify(DynamicMessage.newBuilder(USER)
                        .setField(USER.findFieldByName("email"), "not-an-email")
                        .setField(USER.findFieldByName("age"), 200)
                        .addRepeatedField(USER.findFieldByName("tags"), "a")
                        .build()));

        assertEquals(422, failure.status());
        assertEquals(List.of("age", "email", "tags"),
                failure.violations().stream().map(ValidationException.Violation::path).sorted().toList());
    }

    @Test
    void aMessageThatKeepsTheRulesPasses() {
        assertDoesNotThrow(() -> new ProtovalidateValidation().verify(DynamicMessage.newBuilder(USER)
                .setField(USER.findFieldByName("email"), "a@b.dev")
                .setField(USER.findFieldByName("age"), 30)
                .addRepeatedField(USER.findFieldByName("tags"), "a")
                .addRepeatedField(USER.findFieldByName("tags"), "b")
                .build()));
    }

    @Test
    void theCommonRulesAreSchemaKeywords() {
        Map<String, Object> user = properties(USER, "t.User");

        assertEquals(Map.of("format", "email", "minLength", 3L), user.get("email"));
        assertEquals(Map.of("maximum", 150L, "minimum", 0L), user.get("age"));
        assertEquals(Map.of("minItems", 2L, "uniqueItems", true), user.get("tags"));
    }

    @Test
    void everyOtherRuleIsPublishedToo() {
        Map<String, Object> extras = properties(EXTRAS, "t.Extras");

        assertEquals(Map.of("enum", List.of("a", "b"), "description", "Must start with \"a\".",
                "not", Map.of("enum", List.of("c"))), extras.get("kind"));
        assertEquals(Map.of("enum", List.of(1, 2), "minimum", 0, "exclusiveMinimum", true), extras.get("level"));
        assertEquals(Map.of("items", Map.of("maxLength", 5L), "description", "ids are short"), extras.get("ids"));
        assertEquals(Map.of("minProperties", 1L, "additionalProperties", Map.of("maxLength", 5L),
                "description", "Keys must satisfy {minLength=1}."), extras.get("labels"));
        assertEquals(Map.of("description", "Must be greater than 1.5s."), extras.get("ttl"));
        assertEquals(Map.of("description", "Must be at least 4 bytes.", "enum", List.of("AQI=")), extras.get("raw"));
        assertEquals(Map.of("enum", List.of("GREEN")), extras.get("color"));
        assertEquals(Map.of("properties", Map.of("type_url", Map.of("enum", List.of("type.googleapis.com/t.User")))),
                extras.get("payload"));
        assertEquals(Map.of("minimum", 4294967295L, "exclusiveMinimum", true), extras.get("big"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMessagesOwnRulesAreItsDescription() {
        Map<String, Object> schemas = new LinkedHashMap<>();
        new ProtovalidateDocs().walk(EXTRAS, schemas, new HashSet<>());

        assertEquals("Ids differ from labels. Exactly one of a, b must be set.",
                ((Map<String, Object>) schemas.get("t.Extras")).get("description"));
    }

    @Test
    void aGeneratedClassIsReadThroughItsDescriptorAndAnythingElseIsLeftAlone() {
        ProtovalidateDocs docs = new ProtovalidateDocs();
        docs.used(StringValue.class);
        docs.used(String.class);
        docs.used(com.google.protobuf.Message.class);

        assertEquals(1, docs.roots.size());
        assertEquals(Map.of(), docs.componentContributions());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Descriptor message, String name) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        new ProtovalidateDocs().walk(message, schemas, new HashSet<>());
        return (Map<String, Object>) ((Map<String, Object>) schemas.get(name)).get("properties");
    }

    private static FileDescriptor file() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("t.proto").setPackage("t").setSyntax("proto3")
                .addDependency("buf/validate/validate.proto")
                .addDependency("google/protobuf/any.proto")
                .addDependency("google/protobuf/duration.proto")
                .addEnumType(EnumDescriptorProto.newBuilder().setName("Color")
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(0))
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("GREEN").setNumber(1)))
                .addMessageType(DescriptorProto.newBuilder().setName("User")
                        .addField(field("email", 1, Type.TYPE_STRING, Label.LABEL_OPTIONAL, null,
                                FieldRules.newBuilder().setString(StringRules.newBuilder().setEmail(true).setMinLen(3))))
                        .addField(field("age", 2, Type.TYPE_UINT32, Label.LABEL_OPTIONAL, null,
                                FieldRules.newBuilder().setUint32(UInt32Rules.newBuilder().setGte(0).setLte(150))))
                        .addField(field("tags", 3, Type.TYPE_STRING, Label.LABEL_REPEATED, null,
                                FieldRules.newBuilder().setRepeated(RepeatedRules.newBuilder().setMinItems(2).setUnique(true)))))
                .addMessageType(DescriptorProto.newBuilder().setName("Extras")
                        .setOptions(MessageOptions.newBuilder().setExtension(ValidateProto.message, MessageRules.newBuilder()
                                .addCel(Rule.newBuilder().setId("differ").setMessage("Ids differ from labels."))
                                .addOneof(MessageOneofRule.newBuilder().addFields("a").addFields("b").setRequired(true)).build()))
                        .addField(field("kind", 1, Type.TYPE_STRING, Label.LABEL_OPTIONAL, null, FieldRules.newBuilder()
                                .setString(StringRules.newBuilder().addIn("a").addIn("b").addNotIn("c").setPrefix("a"))))
                        .addField(field("level", 2, Type.TYPE_INT32, Label.LABEL_OPTIONAL, null, FieldRules.newBuilder()
                                .setInt32(Int32Rules.newBuilder().addIn(1).addIn(2).setGt(0))))
                        .addField(field("ids", 3, Type.TYPE_STRING, Label.LABEL_REPEATED, null, FieldRules.newBuilder()
                                .addCel(Rule.newBuilder().setId("short").setMessage("ids are short"))
                                .setRepeated(RepeatedRules.newBuilder().setItems(FieldRules.newBuilder()
                                        .setString(StringRules.newBuilder().setMaxLen(5))))))
                        .addNestedType(DescriptorProto.newBuilder().setName("LabelsEntry")
                                .setOptions(com.google.protobuf.DescriptorProtos.MessageOptions.newBuilder().setMapEntry(true))
                                .addField(field("key", 1, Type.TYPE_STRING, Label.LABEL_OPTIONAL, null, null))
                                .addField(field("value", 2, Type.TYPE_STRING, Label.LABEL_OPTIONAL, null, null)))
                        .addField(field("labels", 4, Type.TYPE_MESSAGE, Label.LABEL_REPEATED, ".t.Extras.LabelsEntry",
                                FieldRules.newBuilder().setMap(MapRules.newBuilder().setMinPairs(1)
                                        .setKeys(FieldRules.newBuilder().setString(StringRules.newBuilder().setMinLen(1)))
                                        .setValues(FieldRules.newBuilder().setString(StringRules.newBuilder().setMaxLen(5))))))
                        .addField(field("ttl", 5, Type.TYPE_MESSAGE, Label.LABEL_OPTIONAL, ".google.protobuf.Duration",
                                FieldRules.newBuilder().setDuration(DurationRules.newBuilder()
                                        .setGt(Duration.newBuilder().setSeconds(1).setNanos(500_000_000)))))
                        .addField(field("raw", 6, Type.TYPE_BYTES, Label.LABEL_OPTIONAL, null, FieldRules.newBuilder()
                                .setBytes(BytesRules.newBuilder().setMinLen(4).setConst(ByteString.copyFrom(new byte[] {1, 2})))))
                        .addField(field("color", 7, Type.TYPE_ENUM, Label.LABEL_OPTIONAL, ".t.Color", FieldRules.newBuilder()
                                .setEnum(EnumRules.newBuilder().setConst(1))))
                        .addField(field("payload", 8, Type.TYPE_MESSAGE, Label.LABEL_OPTIONAL, ".google.protobuf.Any",
                                FieldRules.newBuilder().setAny(AnyRules.newBuilder().addIn("type.googleapis.com/t.User"))))
                        .addField(field("big", 9, Type.TYPE_UINT32, Label.LABEL_OPTIONAL, null, FieldRules.newBuilder()
                                .setUint32(UInt32Rules.newBuilder().setGt(-1)))))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {
                    ValidateProto.getDescriptor(), AnyProto.getDescriptor(), DurationProto.getDescriptor()});
        } catch (Exception broken) {
            throw new AssertionError(broken);
        }
    }

    private static FieldDescriptorProto field(String name, int number, Type type, Label label, String typeName, FieldRules.Builder rules) {
        FieldDescriptorProto.Builder field = FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(number).setType(type).setLabel(label);
        if (typeName != null) field.setTypeName(typeName);
        if (rules != null) field.setOptions(FieldOptions.newBuilder().setExtension(ValidateProto.field, rules.build()));
        return field.build();
    }
}
