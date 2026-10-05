package dev.relism.flash.ext.protobuf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Descriptor-backed OpenAPI schemas for generated protobuf messages. */
public final class ProtobufDocs {

    private ProtobufDocs() {}

    /** Adds descriptor schemas only when OpenAPI is present. */
    public static Module module() {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, Schemas.class));
    }

    static final class Schemas implements OpenApiContributor {

        private final Map<String, Map<String, Object>> components = new LinkedHashMap<>();

        @Override
        public Map<String, Object> schema(Type type) {
            if (!(type instanceof Class<?> cls) || !Message.class.isAssignableFrom(cls)) return null;
            Descriptor descriptor = Protobuf.defaultInstance(cls).getDescriptorForType();
            Map<String, Object> wellKnown = wellKnown(descriptor);
            if (wellKnown != null) return wellKnown;
            register(descriptor);
            return reference(descriptor);
        }

        @Override
        public Map<String, Object> componentContributions() {
            return components.isEmpty() ? Map.of() : Map.of("schemas", components);
        }

        private void register(Descriptor descriptor) {
            String name = descriptor.getFullName();
            if (components.containsKey(name)) return;

            Map<String, Object> schema = new LinkedHashMap<>();
            components.put(name, schema);
            schema.put("type", "object");

            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (FieldDescriptor field : descriptor.getFields()) {
                Map<String, Object> property = fieldSchema(field);
                if (field.getOptions().getDeprecated()) property = with(property, "deprecated", true);
                if (field.hasDefaultValue()) property = with(property, "default", defaultValue(field));
                properties.put(field.getName(), property);
                if (field.isRequired()) required.add(field.getName());
            }
            if (!properties.isEmpty()) schema.put("properties", properties);
            if (!required.isEmpty()) schema.put("required", required);
        }

        private Map<String, Object> fieldSchema(FieldDescriptor field) {
            if (field.isMapField()) {
                FieldDescriptor value = field.getMessageType().findFieldByName("value");
                return Map.of("type", "object", "additionalProperties", singular(value));
            }
            Map<String, Object> singular = singular(field);
            return field.isRepeated() ? Map.of("type", "array", "items", singular) : singular;
        }

        private Map<String, Object> singular(FieldDescriptor field) {
            return switch (field.getJavaType()) {
                case BOOLEAN -> Map.of("type", "boolean");
                case BYTE_STRING -> Map.of("type", "string", "format", "byte");
                case DOUBLE -> Map.of("type", "number", "format", "double");
                case FLOAT -> Map.of("type", "number", "format", "float");
                case INT -> integer(field, "int32");
                case LONG -> integer(field, "int64");
                case STRING -> Map.of("type", "string");
                case ENUM -> enumeration(field);
                case MESSAGE -> message(field.getMessageType());
            };
        }

        private Map<String, Object> integer(FieldDescriptor field, String format) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "integer");
            schema.put("format", format);
            switch (field.getType()) {
                case UINT32, UINT64, FIXED32, FIXED64 -> schema.put("minimum", 0);
                default -> { }
            }
            return schema;
        }

        private Map<String, Object> enumeration(FieldDescriptor field) {
            List<String> values = field.getEnumType().getValues().stream()
                    .map(EnumValueDescriptor::getName)
                    .toList();
            return Map.of("type", "string", "enum", values);
        }

        private Map<String, Object> message(Descriptor descriptor) {
            Map<String, Object> known = wellKnown(descriptor);
            if (known != null) return known;
            register(descriptor);
            return reference(descriptor);
        }

        private static Map<String, Object> reference(Descriptor descriptor) {
            return Map.of("$ref", "#/components/schemas/" + descriptor.getFullName());
        }

        private static Map<String, Object> wellKnown(Descriptor descriptor) {
            return switch (descriptor.getFullName()) {
                case "google.protobuf.Timestamp" -> Map.of("type", "string", "format", "date-time");
                case "google.protobuf.Duration" -> Map.of("type", "string", "format", "duration");
                case "google.protobuf.DoubleValue", "google.protobuf.FloatValue" -> Map.of("type", "number", "nullable", true);
                case "google.protobuf.Int64Value", "google.protobuf.UInt64Value",
                     "google.protobuf.Int32Value", "google.protobuf.UInt32Value" -> Map.of("type", "integer", "nullable", true);
                case "google.protobuf.BoolValue" -> Map.of("type", "boolean", "nullable", true);
                case "google.protobuf.StringValue" -> Map.of("type", "string", "nullable", true);
                case "google.protobuf.BytesValue" -> Map.of("type", "string", "format", "byte", "nullable", true);
                case "google.protobuf.Struct" -> Map.of("type", "object", "additionalProperties", value());
                case "google.protobuf.ListValue" -> Map.of("type", "array", "items", value());
                case "google.protobuf.Value" -> value();
                case "google.protobuf.Any" -> Map.of("type", "object", "properties", Map.of(
                        "type_url", Map.of("type", "string"),
                        "value", Map.of("type", "string", "format", "byte")));
                case "google.protobuf.Empty" -> Map.of("type", "object");
                default -> null;
            };
        }

        private static Map<String, Object> value() {
            return Map.of("nullable", true, "oneOf", List.of(
                    Map.of("type", "string"),
                    Map.of("type", "number"),
                    Map.of("type", "boolean"),
                    Map.of("type", "object"),
                    Map.of("type", "array")));
        }

        static Map<String, Object> with(Map<String, Object> source, String key, Object value) {
            // OpenAPI 3.0 ignores whatever sits beside a $ref, so a reference is wrapped to keep it.
            Map<String, Object> copy = new LinkedHashMap<>();
            if (source.containsKey("$ref")) copy.put("allOf", List.of(source));
            else copy.putAll(source);
            copy.put(key, value);
            return copy;
        }

        private static Object defaultValue(FieldDescriptor field) {
            Object value = field.getDefaultValue();
            return value instanceof EnumValueDescriptor enumeration ? enumeration.getName() : value;
        }
    }
}
