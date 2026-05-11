package dev.relism.flash.ext.data.core;

@FunctionalInterface
public interface Spec<T> {
    String toFragment(SpecContext ctx);

    default Spec<T> and(Spec<T> other) {
        return ctx -> "(" + this.toFragment(ctx) + " AND " + other.toFragment(ctx) + ")";
    }

    default Spec<T> or(Spec<T> other) {
        return ctx -> "(" + this.toFragment(ctx) + " OR " + other.toFragment(ctx) + ")";
    }

    default Spec<T> not() {
        return ctx -> "NOT (" + this.toFragment(ctx) + ")";
    }

    static <T> Spec<T> all() {
        return ctx -> "1=1";
    }

    static <T> Spec<T> none() {
        return ctx -> "1=0";
    }
}
