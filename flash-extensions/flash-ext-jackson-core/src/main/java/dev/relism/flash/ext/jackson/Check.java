package dev.relism.flash.ext.validation;

import java.lang.invoke.MethodHandle;
import java.util.regex.Pattern;

/**
 * One constraint, compiled. Flattened into an opcode plus its operands rather than a class per
 * constraint type: the check loop becomes a {@code tableswitch} over a monomorphic array instead
 * of a megamorphic virtual call, and a passing check touches no allocation at all.
 *
 * <p>Field access goes through a {@link MethodHandle} adapted at compile time to an exact
 * signature — {@code (Object)Object} for reference fields, {@code (Object)long} for primitive
 * integrals — so {@code invokeExact} neither boxes nor allocates an argument array the way
 * {@code Field.get} and {@code Method.invoke} do.
 */
final class Check {

    static final int NOT_NULL = 0;
    static final int NOT_BLANK = 1;
    static final int NOT_EMPTY = 2;
    static final int SIZE = 3;
    static final int RANGE_PRIMITIVE = 4;
    static final int RANGE_BOXED = 5;
    static final int EMAIL = 6;
    static final int PATTERN = 7;

    final int op;
    final String field;
    /** Pre-rendered at compile time, so even the failure path formats nothing. */
    final String message;

    /** {@code (Object)Object} — set for every op except {@link #RANGE_PRIMITIVE}. */
    final MethodHandle ref;
    /** {@code (Object)long} — set only for {@link #RANGE_PRIMITIVE}. */
    final MethodHandle num;

    final int min;
    final int max;
    final long lo;
    final long hi;
    final Pattern pattern;

    private Check(int op, String field, String message, MethodHandle ref, MethodHandle num,
                  int min, int max, long lo, long hi, Pattern pattern) {
        this.op = op;
        this.field = field;
        this.message = message;
        this.ref = ref;
        this.num = num;
        this.min = min;
        this.max = max;
        this.lo = lo;
        this.hi = hi;
        this.pattern = pattern;
    }

    static Check reference(int op, String field, String message, MethodHandle ref) {
        return new Check(op, field, message, ref, null, 0, 0, 0, 0, null);
    }

    static Check size(String field, String message, MethodHandle ref, int min, int max) {
        return new Check(SIZE, field, message, ref, null, min, max, 0, 0, null);
    }

    static Check rangePrimitive(String field, String message, MethodHandle num, long lo, long hi) {
        return new Check(RANGE_PRIMITIVE, field, message, null, num, 0, 0, lo, hi, null);
    }

    static Check rangeBoxed(String field, String message, MethodHandle ref, long lo, long hi) {
        return new Check(RANGE_BOXED, field, message, ref, null, 0, 0, lo, hi, null);
    }

    static Check pattern(String field, String message, MethodHandle ref, Pattern pattern) {
        return new Check(PATTERN, field, message, ref, null, 0, 0, 0, 0, pattern);
    }
}
