package dev.relism.flash.ext.jackson;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The compiled constraints of one type. Built once per class and reused for every request.
 *
 * <p>{@link #verify} allocates nothing when a value passes: the loop walks an array (no iterator),
 * reads fields through exact-signature {@link MethodHandle}s (no boxing, no argument array), and
 * compares against operands resolved at compile time. The violation list and the exception are
 * constructed only once something actually fails.
 */
public final class Validator {

    private static final Check[] NONE = new Check[0];

    /**
     * One compiled validator per type, kept beside the class itself: no map lookup, no lock, and
     * the entry is collected with the class rather than pinning it.
     */
    private static final ClassValue<Validator> COMPILED = new ClassValue<>() {
        @Override protected Validator computeValue(Class<?> type) {
            return compile(type);
        }
    };

    private final Check[] checks;

    private Validator(Check[] checks) {
        this.checks = checks;
    }

    /** The constraints of {@code type}, compiled the first time it is seen and reused after. */
    public static Validator of(Class<?> type) {
        return COMPILED.get(type);
    }

    /**
     * Verifies a value against its own type's constraints.
     *
     * @return {@code value}, so it can be used inline
     * @throws ValidationException 422, listing every failure
     */
    public static <T> T check(T value) {
        of(value.getClass()).verify(value);
        return value;
    }

    /** True when the type declares no constraints at all — {@link #verify} is then a no-op. */
    public boolean isEmpty() {
        return checks.length == 0;
    }

    /**
     * Verifies every constraint on {@code target}.
     *
     * @throws ValidationException with all failures, never just the first
     */
    public void verify(Object target) {
        List<ValidationException.Violation> failures = null;
        for (Check check : checks) {
            if (passes(check, target)) continue;
            if (failures == null) failures = new ArrayList<>(4);
            failures.add(new ValidationException.Violation(check.field, check.message));
        }
        if (failures != null) throw new ValidationException(failures);
    }

    private static boolean passes(Check check, Object target) {
        try {
            if (check.op == Check.RANGE_PRIMITIVE) {
                long value = (long) check.num.invokeExact(target);
                return value >= check.lo && value <= check.hi;
            }
            Object value = (Object) check.ref.invokeExact(target);
            // Jakarta semantics: only @NotNull rejects null; every other constraint passes it.
            return switch (check.op) {
                case Check.NOT_NULL    -> value != null;
                case Check.NOT_BLANK   -> value instanceof String text && !text.isBlank();
                case Check.NOT_EMPTY   -> value != null && sizeOf(value) > 0;
                case Check.SIZE        -> value == null || withinSize(check, value);
                case Check.RANGE_BOXED -> value == null || withinRange(check, (Number) value);
                case Check.EMAIL       -> value == null || (value instanceof String text && isEmail(text));
                case Check.PATTERN     -> value == null
                        || (value instanceof String text && check.pattern.matcher(text).matches());
                default -> true;
            };
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not read " + check.field + " for validation", failure);
        }
    }

    private static boolean withinSize(Check check, Object value) {
        int size = sizeOf(value);
        return size >= check.min && size <= check.max;
    }

    private static boolean withinRange(Check check, Number value) {
        long asLong = value.longValue();
        return asLong >= check.lo && asLong <= check.hi;
    }

    /** No copies: every branch reads a length the object already knows. */
    private static int sizeOf(Object value) {
        if (value instanceof CharSequence text) return text.length();
        if (value instanceof Collection<?> items) return items.size();
        if (value instanceof Map<?, ?> entries) return entries.size();
        if (value instanceof Object[] array) return array.length;
        return 1;
    }

    /**
     * Structural check rather than a regex: {@code Pattern.matcher} allocates a matcher, an int
     * array and a group array on every call, which is exactly the per-request cost this module
     * exists to avoid. {@code indexOf} allocates nothing.
     *
     * <p>Accepts what a mail server would plausibly route and rejects the shapes people actually
     * typo. Deliverability is the confirmation mail's job, not a validator's.
     */
    private static boolean isEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1) return false;
        if (value.indexOf('@', at + 1) >= 0) return false;
        int dot = value.indexOf('.', at + 2);
        return dot > 0 && dot < value.length() - 1 && value.indexOf(' ') < 0;
    }

    // ── Compilation ──────────────────────────────────────────────────────────

    /**
     * Compiles {@code type}'s constraints once.
     *
     * <p>Reads declared fields rather than record accessors: a constraint on a record component
     * propagates to the backing field, so records and plain classes need one code path, not two.
     */
    static Validator compile(Class<?> type) {
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException denied) {
            throw new IllegalStateException(
                    "Cannot read " + type.getName() + " for validation — open its module or package", denied);
        }

        List<Check> checks = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            MethodHandle getter;
            try {
                getter = lookup.unreflectGetter(field);
            } catch (IllegalAccessException denied) {
                continue;
            }
            compileField(field, getter, checks);
        }
        return new Validator(checks.isEmpty() ? NONE : checks.toArray(new Check[0]));
    }

    private static void compileField(Field field, MethodHandle getter, List<Check> checks) {
        String name = field.getName();
        Class<?> type = field.getType();
        MethodHandle ref = type.isPrimitive() ? null : asReference(getter);

        NotNull notNull = field.getAnnotation(NotNull.class);
        if (notNull != null && ref != null)
            checks.add(Check.reference(Check.NOT_NULL, name, said(notNull.message(), "must not be null"), ref));

        NotBlank notBlank = field.getAnnotation(NotBlank.class);
        if (notBlank != null && ref != null)
            checks.add(Check.reference(Check.NOT_BLANK, name, said(notBlank.message(), "must not be blank"), ref));

        NotEmpty notEmpty = field.getAnnotation(NotEmpty.class);
        if (notEmpty != null && ref != null)
            checks.add(Check.reference(Check.NOT_EMPTY, name, said(notEmpty.message(), "must not be empty"), ref));

        Size size = field.getAnnotation(Size.class);
        if (size != null && ref != null)
            checks.add(Check.size(name, said(size.message(), sizeMessage(size)), ref, size.min(), size.max()));

        Min min = field.getAnnotation(Min.class);
        Max max = field.getAnnotation(Max.class);
        if (min != null || max != null) {
            long lo = min != null ? min.value() : Long.MIN_VALUE;
            long hi = max != null ? max.value() : Long.MAX_VALUE;
            String message = said(min != null ? min.message() : max.message(), rangeMessage(min, max));
            if (isIntegralPrimitive(type)) {
                checks.add(Check.rangePrimitive(name, message, asLong(getter), lo, hi));
            } else if (Number.class.isAssignableFrom(type) && ref != null) {
                checks.add(Check.rangeBoxed(name, message, ref, lo, hi));
            }
        }

        Email email = field.getAnnotation(Email.class);
        if (email != null && ref != null)
            checks.add(Check.reference(Check.EMAIL, name, said(email.message(), "must be a well-formed email address"), ref));

        Pattern pattern = field.getAnnotation(Pattern.class);
        if (pattern != null && ref != null) {
            // ponytail: the one allocating check — Pattern.matcher() per call. The regex itself is
            // compiled once here; swap for a structural check if a hot route ever needs it.
            checks.add(Check.pattern(name, said(pattern.message(), "must match " + pattern.regexp()), ref,
                    java.util.regex.Pattern.compile(pattern.regexp())));
        }
    }

    private static boolean isIntegralPrimitive(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class;
    }

    private static MethodHandle asReference(MethodHandle getter) {
        return getter.asType(MethodType.methodType(Object.class, Object.class));
    }

    private static MethodHandle asLong(MethodHandle getter) {
        return getter.asType(MethodType.methodType(long.class, Object.class));
    }

    /**
     * What a failure says: the constraint's own {@code message} when it sets one, and otherwise a
     * plain description of the rule. Jakarta's defaults are bundle keys in braces, and a key is
     * not something to put in front of whoever sent the request.
     */
    private static String said(String message, String otherwise) {
        return message == null || message.isBlank() || message.startsWith("{") ? otherwise : message;
    }

    private static String sizeMessage(Size size) {
        if (size.min() == 0) return "size must be at most " + size.max();
        if (size.max() == Integer.MAX_VALUE) return "size must be at least " + size.min();
        return "size must be between " + size.min() + " and " + size.max();
    }

    private static String rangeMessage(Min min, Max max) {
        if (min == null) return "must be at most " + max.value();
        if (max == null) return "must be at least " + min.value();
        return "must be between " + min.value() + " and " + max.value();
    }
}
