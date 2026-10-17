package dev.relism.flash.ext.validation.avaje;

import dev.relism.flash.ext.validation.Constraints;
import dev.relism.flash.ext.validation.Facts;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.ext.validation.ValidationException;
import dev.relism.flash.ext.validation.ValidationException.Violation;
import dev.relism.flash.App;
import dev.relism.flash.Module;
import io.avaje.validation.ConstraintViolation;
import io.avaje.validation.Validator;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Avaje Validator: the same constraints, checked by adapters generated at build time.
 *
 * <pre>{@code
 * app.install(new AvajeValidation());
 * }</pre>
 *
 * <p>No reflection at runtime, which is the reason to choose it — and the reason it asks something
 * of the build. {@code avaje-validator-generator} must run in the module that owns the validated
 * types, and each of those types carries {@code io.avaje.validation.constraints.Valid} so an adapter
 * is written for it; the constraints themselves may stay jakarta's. A type that declares constraints
 * but has no adapter is not skipped quietly: the engine refuses it by name on the first request. A
 * type that declares none has nothing to check, and the generator writes no adapter for it.
 */
public class AvajeValidation implements Module, Validation {

    private final Validator delegate;

    /** Types with no adapter and no constraint, met once: nothing to check, so no further lookups. */
    private final Set<Class<?>> unconstrained = ConcurrentHashMap.newKeySet();

    public AvajeValidation() {
        this(Validator.builder().build());
    }

    /** With a validator configured by hand — a locale, a clock, a message bundle. */
    public AvajeValidation(Validator delegate) {
        this.delegate = delegate;
    }

    /** Adds itself as the app's {@link Validation}, and its rules to the published document. */
    @Override
    public void install(App app) {
        app.add(Validation.class, this).install(Validation.docs());
    }

    /**
     * {@code check} rather than {@code validate}: every failure at once, and no exception as a signal.
     * A collection is checked element by element, each failure's path prefixed with its index.
     */
    @Override
    public void verify(Object value) {
        List<Violation> failures = new ArrayList<>();
        if (value instanceof Collection<?> items) {
            int index = 0;
            for (Object item : items) check(item, "[" + index++ + "].", failures);
        } else {
            check(value, "", failures);
        }
        if (!failures.isEmpty()) throw new ValidationException(failures);
    }

    private void check(Object value, String prefix, List<Violation> failures) {
        if (value == null || unconstrained.contains(value.getClass())) return;
        Set<ConstraintViolation> broken;
        try {
            broken = delegate.check(value);
        } catch (IllegalArgumentException missing) {
            // Avaje's only signal that no adapter was generated for the type.
            String message = missing.getMessage();
            if (message == null || !message.startsWith("No ValidationAdapter for")) throw missing;
            if (constrained(value.getClass())) throw new IllegalStateException(value.getClass().getName()
                    + " declares constraints but has no generated adapter: annotate it with"
                    + " io.avaje.validation.constraints.Valid", missing);
            unconstrained.add(value.getClass());
            return;
        }
        for (ConstraintViolation violation : broken) {
            failures.add(new Violation(prefix + violation.path(), violation.message()));
        }
    }

    /** Whether a constraint, jakarta's or Avaje's or one built on them, sits on the type or a field of it. */
    private static boolean constrained(Class<?> type) {
        if (constraint(type.getAnnotations())) return true;
        for (Field field : type.getDeclaredFields()) {
            if (constraint(field.getAnnotations()) || constraint(field.getAnnotatedType().getAnnotations())) return true;
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (constraint(component.getAnnotations())) return true;
            }
        }
        return false;
    }

    private static boolean constraint(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> kind = annotation.annotationType();
            if (constraintPackage(kind.getPackageName())) return true;
            for (Annotation meta : kind.getAnnotations()) {
                if (meta.annotationType().getSimpleName().equals("Constraint")
                        && constraintPackage(meta.annotationType().getPackageName())) return true;
            }
        }
        return false;
    }

    private static boolean constraintPackage(String name) {
        return name.equals("jakarta.validation.constraints") || name.equals("jakarta.validation")
                || name.equals("io.avaje.validation.constraints");
    }

    /**
     * What each constraint adds to a schema: the jakarta ones this engine also enforces, and the
     * ones that are its own.
     */
    @Override
    public void describe(Constraints constraints) {
        jakarta(constraints);
        avaje(constraints);
    }

    private static void jakarta(Constraints constraints) {
        constraints.of(jakarta.validation.constraints.NotNull.class, (rule, facts) -> facts.required());
        constraints.of(jakarta.validation.constraints.NotBlank.class, (rule, facts) -> {
            facts.required();
            facts.put("minLength", 1);
        });
        constraints.of(jakarta.validation.constraints.NotEmpty.class, (rule, facts) -> {
            facts.required();
            Bounds bounds = bounds(facts);
            if (bounds != null) facts.put(bounds.min(), 1);
        });
        constraints.of(jakarta.validation.constraints.Size.class, (rule, facts) -> size(facts, rule.min(), rule.max()));
        constraints.of(jakarta.validation.constraints.Min.class, (rule, facts) -> facts.put("minimum", rule.value()));
        constraints.of(jakarta.validation.constraints.Max.class, (rule, facts) -> facts.put("maximum", rule.value()));
        constraints.of(jakarta.validation.constraints.Email.class, (rule, facts) -> facts.put("format", "email"));
        constraints.of(jakarta.validation.constraints.Pattern.class, (rule, facts) -> {
            facts.put("pattern", rule.regexp());
            facts.describe(rule.message());
        });
    }

    private static void avaje(Constraints constraints) {
        constraints.of(io.avaje.validation.constraints.NotNull.class, (rule, facts) -> facts.required());
        constraints.of(io.avaje.validation.constraints.NotBlank.class, (rule, facts) -> {
            facts.required();
            facts.put("minLength", 1);
            if (rule.max() > 0) facts.put("maxLength", rule.max());     // its own: blankness and a bound in one
        });
        constraints.of(io.avaje.validation.constraints.NotEmpty.class, (rule, facts) -> {
            facts.required();
            Bounds bounds = bounds(facts);
            if (bounds != null) facts.put(bounds.min(), 1);
        });
        constraints.of(io.avaje.validation.constraints.Size.class, (rule, facts) -> size(facts, rule.min(), rule.max()));
        constraints.of(io.avaje.validation.constraints.Length.class,
                (rule, facts) -> size(facts, rule.min(), rule.max()));
        constraints.of(io.avaje.validation.constraints.Min.class, (rule, facts) -> facts.put("minimum", rule.value()));
        constraints.of(io.avaje.validation.constraints.Max.class, (rule, facts) -> facts.put("maximum", rule.value()));
        constraints.of(io.avaje.validation.constraints.DecimalMin.class, (rule, facts) -> {
            facts.put("minimum", new BigDecimal(rule.value()));
            if (!rule.inclusive()) facts.put("exclusiveMinimum", true);
        });
        constraints.of(io.avaje.validation.constraints.DecimalMax.class, (rule, facts) -> {
            facts.put("maximum", new BigDecimal(rule.value()));
            if (!rule.inclusive()) facts.put("exclusiveMaximum", true);
        });
        constraints.of(io.avaje.validation.constraints.Positive.class, (rule, facts) -> {
            facts.put("minimum", 0);
            facts.put("exclusiveMinimum", true);
        });
        constraints.of(io.avaje.validation.constraints.PositiveOrZero.class, (rule, facts) -> facts.put("minimum", 0));
        constraints.of(io.avaje.validation.constraints.Negative.class, (rule, facts) -> {
            facts.put("maximum", 0);
            facts.put("exclusiveMaximum", true);
        });
        constraints.of(io.avaje.validation.constraints.NegativeOrZero.class, (rule, facts) -> facts.put("maximum", 0));
        constraints.of(io.avaje.validation.constraints.Email.class, (rule, facts) -> facts.put("format", "email"));
        constraints.of(io.avaje.validation.constraints.URI.class, (rule, facts) -> facts.put("format", "uri"));
        constraints.of(io.avaje.validation.constraints.UUID.class, (rule, facts) -> facts.put("format", "uuid"));
        constraints.of(io.avaje.validation.constraints.Pattern.class, (rule, facts) -> {
            facts.put("pattern", rule.regexp());
            facts.describe(rule.message());
        });
    }

    private static void size(Facts facts, int min, int max) {
        Bounds bounds = bounds(facts);
        if (bounds == null) return;
        if (min > 0) facts.put(bounds.min(), min);
        if (max != Integer.MAX_VALUE && max > 0) facts.put(bounds.max(), max);
    }

    /** What a size means for this property: characters, items, or entries. Null when it means nothing. */
    private static Bounds bounds(Facts facts) {
        Class<?> type = facts.type();
        if (CharSequence.class.isAssignableFrom(type)) return new Bounds("minLength", "maxLength");
        if (Collection.class.isAssignableFrom(type) || type.isArray()) return new Bounds("minItems", "maxItems");
        if (Map.class.isAssignableFrom(type)) return new Bounds("minProperties", "maxProperties");
        return null;
    }

    private record Bounds(String min, String max) {}
}
