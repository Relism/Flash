package dev.relism.flash.ext.openapi;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Mirrors {@code jakarta.validation} constraints into the generated schema, so a type carries its
 * rules once and both the validator and the published contract read them.
 *
 * <p>Loaded reflectively by {@link OpenApiBuilder} and used only when the annotations are on the
 * classpath — this class is never touched otherwise, so {@code flash-ext-openapi} keeps working
 * with no validation dependency at all. Nothing to install and nothing to configure: if the
 * annotations are there, the schema gains {@code minLength}, {@code maximum}, {@code format} and
 * {@code required} on its own.
 */
final class ConstraintHints {

    private ConstraintHints() {}

    /** True when jakarta.validation is resolvable, so the caller may use this class. */
    static boolean available() {
        try {
            Class.forName("jakarta.validation.constraints.NotNull", false, ConstraintHints.class.getClassLoader());
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /**
     * Merges {@code field}'s constraints into {@code property}, and reports whether the field is
     * required. Never overwrites a key an explicit {@code @Schema} already set.
     */
    static boolean apply(Field field, Map<String, Object> property) {
        boolean isString = "string".equals(property.get("type"));

        Size size = field.getAnnotation(Size.class);
        if (size != null) {
            if (isString) {
                if (size.min() > 0) property.putIfAbsent("minLength", size.min());
                if (size.max() != Integer.MAX_VALUE) property.putIfAbsent("maxLength", size.max());
            } else if ("array".equals(property.get("type"))) {
                if (size.min() > 0) property.putIfAbsent("minItems", size.min());
                if (size.max() != Integer.MAX_VALUE) property.putIfAbsent("maxItems", size.max());
            }
        }

        Min min = field.getAnnotation(Min.class);
        if (min != null) property.putIfAbsent("minimum", min.value());

        Max max = field.getAnnotation(Max.class);
        if (max != null) property.putIfAbsent("maximum", max.value());

        if (field.isAnnotationPresent(Email.class)) property.putIfAbsent("format", "email");

        Pattern pattern = field.getAnnotation(Pattern.class);
        if (pattern != null) {
            property.putIfAbsent("pattern", pattern.regexp());
            describe(property, pattern.message());
        }

        if (field.isAnnotationPresent(NotBlank.class) && isString) property.putIfAbsent("minLength", 1);
        if (field.isAnnotationPresent(NotEmpty.class)) {
            if (isString) property.putIfAbsent("minLength", 1);
            else if ("array".equals(property.get("type"))) property.putIfAbsent("minItems", 1);
        }

        return field.isAnnotationPresent(NotNull.class)
                || field.isAnnotationPresent(NotBlank.class)
                || field.isAnnotationPresent(NotEmpty.class);
    }

    /** Constraint annotations this bridge understands, for documentation and tests. */
    /**
     * Says in words what a regex says in symbols.
     *
     * <p>Only {@code @Pattern} needs this: every other constraint has a keyword a reader
     * understands — {@code required}, {@code maxLength}, {@code format: email} — and repeating it
     * as prose would be noise. A regex has none, so the rule's own message goes in the
     * description beside it, after whatever the property already said.
     */
    private static void describe(Map<String, Object> property, String message) {
        if (message == null || message.isBlank() || message.startsWith("{")) return;   // jakarta's default is a bundle key

        String sentence = Character.toUpperCase(message.charAt(0)) + message.substring(1);
        if (!sentence.endsWith(".")) sentence += ".";

        Object said = property.get("description");
        if (said == null) property.put("description", sentence);
        else if (!said.toString().contains(sentence)) property.put("description", said + " " + sentence);
    }

    static List<String> supported() {
        return List.of("@NotNull", "@NotBlank", "@NotEmpty", "@Size", "@Min", "@Max", "@Email", "@Pattern");
    }
}
