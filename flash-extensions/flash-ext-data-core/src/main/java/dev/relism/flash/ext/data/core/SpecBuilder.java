package dev.relism.flash.ext.data.core;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SpecBuilder<T> {
    private SpecBuilder() {}

    public static <T, V> FieldSpec<T, V> field(String column) {
        return new FieldSpec<>(column);
    }

    public static final class FieldSpec<T, V> {
        private final String column;

        private FieldSpec(String column) {
            this.column = Objects.requireNonNull(column);
        }

        public Spec<T> eq(V value) { return ctx -> column + " = " + ctx.bind(value); }
        public Spec<T> neq(V value) { return ctx -> column + " != " + ctx.bind(value); }
        public Spec<T> like(String pattern) { return ctx -> column + " like " + ctx.bind(pattern); }
        public Spec<T> isNull() { return ctx -> column + " is null"; }
        public Spec<T> isNotNull() { return ctx -> column + " is not null"; }

        public Spec<T> in(Collection<V> values) {
            return ctx -> column + " in (" + values.stream().map(ctx::bind).collect(Collectors.joining(", ")) + ")";
        }

        public <C extends Comparable<C>> Spec<T> gt(C value) { return ctx -> column + " > " + ctx.bind(value); }
        public <C extends Comparable<C>> Spec<T> lt(C value) { return ctx -> column + " < " + ctx.bind(value); }
        public <C extends Comparable<C>> Spec<T> between(C lo, C hi) {
            return ctx -> column + " between " + ctx.bind(lo) + " and " + ctx.bind(hi);
        }
    }
}
