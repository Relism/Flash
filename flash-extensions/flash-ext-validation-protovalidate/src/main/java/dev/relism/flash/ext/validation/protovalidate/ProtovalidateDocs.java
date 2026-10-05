package dev.relism.flash.ext.validation.protovalidate;

import build.buf.validate.FieldRules;
import build.buf.validate.MessageOneofRule;
import build.buf.validate.MessageRules;
import build.buf.validate.OneofRules;
import build.buf.validate.Rule;
import build.buf.validate.ValidateProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.FieldMask;
import com.google.protobuf.GeneratedMessage.ExtendableMessage;
import com.google.protobuf.GeneratedMessage.GeneratedExtension;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.Timestamp;
import dev.relism.flash.ext.openapi.OpenApiContributor;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Map.entry;

/**
 * Says in the published document every rule Protovalidate enforces: a schema keyword where
 * OpenAPI has one, a sentence in the description where it does not. Whoever describes a message's
 * shape, this adds the rules to the same component schemas, which the document merges by name.
 */
final class ProtovalidateDocs implements OpenApiContributor {

    private static final ExtensionRegistry RULES = rules();

    /** A rule that is one schema keyword. */
    private static final Map<String, String> KEYWORDS = Map.ofEntries(
            entry("gte", "minimum"), entry("lte", "maximum"),
            entry("min_len", "minLength"), entry("max_len", "maxLength"), entry("pattern", "pattern"),
            entry("min_items", "minItems"), entry("max_items", "maxItems"),
            entry("min_pairs", "minProperties"), entry("max_pairs", "maxProperties"));

    /** A rule that is a format, as a boolean. */
    private static final Set<String> FORMATS = Set.of("email", "hostname", "ip", "ipv4", "ipv6", "uri", "uri_ref",
            "address", "uuid", "tuuid", "ulid", "host_and_port", "protobuf_fqn", "protobuf_dot_fqn",
            "ip_with_prefixlen", "ipv4_with_prefixlen", "ipv6_with_prefixlen", "ip_prefix", "ipv4_prefix", "ipv6_prefix");

    /** A rule OpenAPI has no keyword for, said in words; {@code %s} is the rule's value. */
    private static final Map<String, String> SENTENCES = Map.ofEntries(
            entry("gt", "Must be greater than %s."), entry("gte", "Must be at least %s."),
            entry("lt", "Must be less than %s."), entry("lte", "Must be at most %s."),
            entry("within", "Must be within %s of now."), entry("lt_now", "Must be in the past."),
            entry("gt_now", "Must be in the future."), entry("finite", "Must be finite."),
            entry("len", "Must be %s bytes long."), entry("min_len", "Must be at least %s bytes."),
            entry("max_len", "Must be at most %s bytes."), entry("len_bytes", "Must be %s bytes long."),
            entry("min_bytes", "Must be at least %s bytes."), entry("max_bytes", "Must be at most %s bytes."),
            entry("pattern", "Must match \"%s\"."), entry("prefix", "Must start with \"%s\"."),
            entry("suffix", "Must end with \"%s\"."), entry("contains", "Must contain \"%s\"."),
            entry("not_contains", "Must not contain \"%s\"."), entry("well_known_regex", "Must be a valid %s."));

    final Set<Descriptor> roots = ConcurrentHashMap.newKeySet();

    @Override
    public void used(Type type) {
        if (!(type instanceof Class<?> cls) || !Message.class.isAssignableFrom(cls)) return;
        try {
            roots.add((Descriptor) cls.getMethod("getDescriptor").invoke(null));
        } catch (ReflectiveOperationException | ClassCastException notGenerated) {
            // Message itself: no descriptor to read from the class.
        }
    }

    @Override
    public Map<String, Object> componentContributions() {
        Map<String, Object> schemas = new LinkedHashMap<>();
        Set<Descriptor> walked = new HashSet<>();
        for (Descriptor root : roots) walk(root, schemas, walked);
        return schemas.isEmpty() ? Map.of() : Map.of("schemas", schemas);
    }

    void walk(Descriptor message, Map<String, Object> schemas, Set<Descriptor> walked) {
        if (!walked.add(message)) return;

        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<String> said = new ArrayList<>();

        MessageRules own = extension(message.getOptions(), ValidateProto.message);
        if (own != null) {
            cel(own.getCelList(), own.getCelExpressionList(), said);
            for (MessageOneofRule one : own.getOneofList())
                said.add((one.getRequired() ? "Exactly one of %s must be set." : "At most one of %s may be set.")
                        .formatted(String.join(", ", one.getFieldsList())));
        }
        for (OneofDescriptor oneof : message.getOneofs()) {
            OneofRules rules = extension(oneof.getOptions(), ValidateProto.oneof);
            if (rules != null && rules.getRequired())
                said.add("Exactly one of %s must be set.".formatted(
                        String.join(", ", oneof.getFields().stream().map(FieldDescriptor::getName).toList())));
        }
        for (FieldDescriptor field : message.getFields()) {
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) walk(field.getMessageType(), schemas, walked);

            FieldRules rules = extension(field.getOptions(), ValidateProto.field);
            if (rules == null) continue;
            Map<String, Object> facts = facts(rules, field);
            if (!facts.isEmpty()) properties.put(field.getName(), facts);
            if (rules.getRequired()) required.add(field.getName());
        }

        if (!said.isEmpty()) schema.put("description", String.join(" ", said));
        if (!properties.isEmpty()) schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        if (!schema.isEmpty()) schemas.put(message.getFullName(), schema);
    }

    private static Map<String, Object> facts(FieldRules rules, FieldDescriptor field) {
        Map<String, Object> facts = new LinkedHashMap<>();
        List<String> said = new ArrayList<>();
        cel(rules.getCelList(), rules.getCelExpressionList(), said);
        for (Object typed : rules.getAllFields().values()) {
            if (typed instanceof Message set) set.getAllFields().forEach((rule, value) -> fact(rule, value, field, facts, said));
        }
        if (!said.isEmpty()) facts.put("description", String.join(" ", said));
        return facts;
    }

    private static void fact(FieldDescriptor rule, Object value, FieldDescriptor field, Map<String, Object> facts, List<String> said) {
        String name = rule.getName();
        String owner = rule.getContainingType().getName();
        boolean bytes = owner.equals("BytesRules");
        if (value instanceof FieldRules inner) {
            switch (name) {
                case "items" -> facts.put("items", facts(inner, field));
                case "values" -> facts.put("additionalProperties",
                        facts(inner, field.isMapField() ? field.getMessageType().findFieldByName("value") : field));
                default -> said.add("Keys must satisfy " + facts(inner, field) + ".");
            }
            return;
        }

        Object json = value instanceof List<?> list ? list.stream().map(item -> json(rule, field, item)).toList() : json(rule, field, value);
        Map<String, Object> into = owner.equals("AnyRules") ? typeUrl(facts) : facts;
        String keyword = KEYWORDS.get(name);
        if (name.equals("const")) into.put("enum", List.of(json));
        else if (name.equals("in")) into.put("enum", json);
        else if (name.equals("not_in")) into.put("not", Map.of("enum", json));
        else if (name.equals("example")) { if (!((List<?>) json).isEmpty()) facts.put("example", ((List<?>) json).getFirst()); }
        else if (name.equals("unique")) { if (json.equals(true)) facts.put("uniqueItems", true); }
        else if ((name.equals("gt") || name.equals("lt")) && json instanceof Number) {
            facts.put(name.equals("gt") ? "minimum" : "maximum", json);
            facts.put(name.equals("gt") ? "exclusiveMinimum" : "exclusiveMaximum", true);
        }
        else if (name.equals("len") && !bytes) { facts.put("minLength", json); facts.put("maxLength", json); }
        else if (keyword != null && !bytes && !(value instanceof Message)) facts.put(keyword, json);
        else if (FORMATS.contains(name)) {
            if (json.equals(true)) {
                if (bytes) said.add("Must be an " + name + " as raw bytes.");
                else facts.put("format", name.equals("uri_ref") ? "uri-reference" : name.replace('_', '-'));
            }
        }
        else if (SENTENCES.containsKey(name) && !json.equals(false)) said.add(SENTENCES.get(name).formatted(json));
    }

    /** What a rule's value is in a document: bytes as base64, an enum by name, a well-known message as its JSON text. */
    private static Object json(FieldDescriptor rule, FieldDescriptor field, Object value) {
        if (value instanceof ByteString bytes) return Base64.getEncoder().encodeToString(bytes.toByteArray());
        if (value instanceof Message message) return text(message);
        if (value instanceof EnumValueDescriptor named) return named.getName().toLowerCase().replace('_', ' ');
        if (rule.getContainingType().getName().equals("EnumRules") && value instanceof Integer number) {
            EnumValueDescriptor named = field.getEnumType().findValueByNumber(number);
            return named == null ? number : named.getName();
        }
        return switch (rule.getType()) {
            case UINT32, FIXED32 -> Integer.toUnsignedLong((Integer) value);
            case UINT64, FIXED64 -> (Long) value >= 0 ? value : new BigInteger(Long.toUnsignedString((Long) value));
            default -> value;
        };
    }

    private static String text(Message message) {
        return switch (message) {
            case Duration d -> BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNanos(), 9))
                    .stripTrailingZeros().toPlainString() + "s";
            case Timestamp t -> Instant.ofEpochSecond(t.getSeconds(), t.getNanos()).toString();
            case FieldMask m -> String.join(",", m.getPathsList());
            default -> TextFormat.shortDebugString(message);
        };
    }

    /** An {@code Any} is an object, so its allowed type URLs bound that property. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> typeUrl(Map<String, Object> facts) {
        Map<String, Object> properties = (Map<String, Object>) facts.computeIfAbsent("properties", k -> new LinkedHashMap<>());
        return (Map<String, Object>) properties.computeIfAbsent("type_url", k -> new LinkedHashMap<String, Object>());
    }

    private static void cel(List<Rule> rules, List<String> expressions, List<String> said) {
        for (Rule rule : rules) said.add(rule.hasMessage() ? rule.getMessage() : rule.getExpression());
        said.addAll(expressions);
    }

    /** The rules an options message carries as an extension, which a plain parse leaves unknown. */
    @SuppressWarnings("unchecked")
    private static <O extends ExtendableMessage<O>, V> V extension(O options, GeneratedExtension<O, V> extension) {
        if (options.getSerializedSize() == 0) return null;
        try {
            O parsed = (O) options.getParserForType().parseFrom(options.toByteString(), RULES);
            return parsed.hasExtension(extension) ? parsed.getExtension(extension) : null;
        } catch (InvalidProtocolBufferException broken) {
            return null;
        }
    }

    private static ExtensionRegistry rules() {
        ExtensionRegistry registry = ExtensionRegistry.newInstance();
        ValidateProto.registerAllExtensions(registry);
        return registry;
    }
}
