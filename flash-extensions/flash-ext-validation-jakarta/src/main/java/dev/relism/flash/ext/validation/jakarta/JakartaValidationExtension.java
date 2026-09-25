package dev.relism.flash.ext.validation.jakarta;

import dev.relism.flash.ext.validation.Constraints;
import dev.relism.flash.ext.validation.Facts;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.ext.validation.ValidationException;
import dev.relism.flash.ext.validation.ValidationException.Violation;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Jakarta Validation, as Hibernate Validator implements it.
 *
 * <pre>{@code
 * app.install(new JakartaValidationExtension());
 * }</pre>
 *
 * <p>Every constraint of the specification, cascading through {@code @Valid}, groups, custom
 * constraints and localized messages — all of it the engine's, none of it Flash's. Nothing is asked
 * of the build: constraints are read by reflection at runtime, which is what makes this the engine
 * to reach for first.
 */
public class JakartaValidationExtension implements FlashExtension, Validation {

    private final jakarta.validation.Validator delegate;

    public JakartaValidationExtension() {
        this(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
    }

    /** With a validator configured by hand — a message interpolator, a clock, a parameter provider. */
    public JakartaValidationExtension(jakarta.validation.Validator delegate) {
        this.delegate = delegate;
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        Validation.install(ctx, this);
    }

    @Override
    public void verify(Object value) {
        Set<ConstraintViolation<Object>> broken = delegate.validate(value);
        if (broken.isEmpty()) return;

        List<Violation> failures = new ArrayList<>(broken.size());
        for (ConstraintViolation<?> violation : broken) {
            failures.add(new Violation(violation.getPropertyPath().toString(), violation.getMessage()));
        }
        throw new ValidationException(failures);
    }

    /**
     * What each constraint adds to a schema.
     *
     * <p>Only what a schema keyword can carry: a temporal bound, a digit count or an assertion has
     * no counterpart, and is enforced without being documented.
     */
    @Override
    public void describe(Constraints constraints) {
        constraints.of(NotNull.class, (rule, facts) -> facts.required());
        constraints.of(NotBlank.class, (rule, facts) -> {
            facts.required();
            facts.put("minLength", 1);          // jakarta allows it on text only
        });
        constraints.of(NotEmpty.class, (rule, facts) -> {
            facts.required();
            Bounds bounds = bounds(facts);
            if (bounds != null) facts.put(bounds.min(), 1);
        });
        constraints.of(Size.class, (rule, facts) -> {
            Bounds bounds = bounds(facts);
            if (bounds == null) return;
            if (rule.min() > 0) facts.put(bounds.min(), rule.min());
            if (rule.max() != Integer.MAX_VALUE) facts.put(bounds.max(), rule.max());
        });
        constraints.of(Min.class, (rule, facts) -> facts.put("minimum", rule.value()));
        constraints.of(Max.class, (rule, facts) -> facts.put("maximum", rule.value()));
        constraints.of(DecimalMin.class, (rule, facts) -> {
            facts.put("minimum", new BigDecimal(rule.value()));
            if (!rule.inclusive()) facts.put("exclusiveMinimum", true);
        });
        constraints.of(DecimalMax.class, (rule, facts) -> {
            facts.put("maximum", new BigDecimal(rule.value()));
            if (!rule.inclusive()) facts.put("exclusiveMaximum", true);
        });
        constraints.of(Positive.class, (rule, facts) -> {
            facts.put("minimum", 0);
            facts.put("exclusiveMinimum", true);
        });
        constraints.of(PositiveOrZero.class, (rule, facts) -> facts.put("minimum", 0));
        constraints.of(Negative.class, (rule, facts) -> {
            facts.put("maximum", 0);
            facts.put("exclusiveMaximum", true);
        });
        constraints.of(NegativeOrZero.class, (rule, facts) -> facts.put("maximum", 0));
        constraints.of(Email.class, (rule, facts) -> facts.put("format", "email"));
        constraints.of(Pattern.class, (rule, facts) -> {
            facts.put("pattern", rule.regexp());
            facts.describe(rule.message());
        });
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
