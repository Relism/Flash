package dev.relism.flash.ext.validation;

import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiContributorRegistry;
import dev.relism.flash.extension.FlashContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Carries what the installed engine said about its rules into the published document.
 *
 * <p>The walk is here because it is the same for every engine — a property, its annotations, the
 * facts they add. Which annotations exist and what they mean is never here: it arrives through
 * {@link Constraints} when the engine is installed.
 *
 * <p>Built once per document revision, so nothing on this path is on a request.
 */
final class ValidationDocs {

    private ValidationDocs() {}

    static void register(FlashContext ctx, Validation validation) {
        Declared declared = new Declared();
        validation.describe(declared);
        if (declared.mappers.isEmpty()) return;

        ctx.onReady(() -> {
            try {
                OpenApi.add(ctx, declared);
            } catch (NoClassDefFoundError absent) {
                // flash-ext-openapi is not on the classpath
            }
        });
    }

    /** What the engine registered, and the walk that applies it to one property. */
    private static final class Declared implements Constraints {

        private final Map<Class<? extends Annotation>, BiConsumer<Annotation, Facts>> mappers = new HashMap<>();

        @Override
        public <A extends Annotation> void of(Class<A> annotation, BiConsumer<A, Facts> facts) {
            mappers.put(annotation, (present, sink) -> facts.accept(annotation.cast(present), sink));
        }

        /** Merges every rule on {@code field} into {@code property}; true when the property is required. */
        boolean apply(Field field, Map<String, Object> property) {
            Sink sink = new Sink(field.getType(), property);
            for (Annotation present : field.getAnnotations()) {
                BiConsumer<Annotation, Facts> mapper = mappers.get(present.annotationType());
                if (mapper != null) mapper.accept(present, sink);
            }
            return sink.required;
        }
    }

    /** One property's schema, open for facts. */
    private static final class Sink implements Facts {

        private final Class<?> type;
        private final Map<String, Object> property;
        private boolean required;

        private Sink(Class<?> type, Map<String, Object> property) {
            this.type = type;
            this.property = property;
        }

        @Override public Class<?> type() {
            return type;
        }

        /** Never overwrites what an explicit annotation already said. */
        @Override public void put(String keyword, Object value) {
            property.putIfAbsent(keyword, value);
        }

        @Override public void describe(String sentence) {
            // A message that is a bundle key is not prose: both engines write their defaults that way.
            if (sentence == null || sentence.isBlank() || sentence.startsWith("{")) return;

            String said = Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
            if (!said.endsWith(".")) said += ".";

            Object already = property.get("description");
            if (already == null) property.put("description", said);
            else if (!already.toString().contains(said)) property.put("description", already + " " + said);
        }

        @Override public void required() {
            required = true;
        }
    }

    /** Isolated so this module loads without flash-ext-openapi on the classpath. */
    private static final class OpenApi {

        static void add(FlashContext ctx, Declared declared) {
            ctx.find(OpenApiContributorRegistry.class).ifPresent(registry -> registry.add(new OpenApiContributor() {
                @Override public boolean property(Field field, Map<String, Object> schema) {
                    return declared.apply(field, schema);
                }
            }));
        }
    }
}
