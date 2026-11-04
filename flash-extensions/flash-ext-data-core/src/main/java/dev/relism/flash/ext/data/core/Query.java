package dev.relism.flash.ext.data.core;

/** What {@code findAll} reads: the rows {@code spec} matches, in {@code sort} order, at most {@code limit} of them (0: all). */
public record Query<T>(Spec<T> spec, Sort sort, int limit) {

    public Query {
        spec = spec == null ? Spec.all() : spec;
        sort = sort == null ? Sort.unsorted() : sort;
        if (limit < 0) throw new IllegalArgumentException("limit is negative: " + limit);
    }

    public static <T> Query<T> all() {
        return new Query<>(Spec.all(), Sort.unsorted(), 0);
    }

    public Query<T> where(Spec<T> spec) {
        return new Query<>(spec, sort, limit);
    }

    public Query<T> orderBy(Sort sort) {
        return new Query<>(spec, sort, limit);
    }

    public Query<T> limit(int limit) {
        return new Query<>(spec, sort, limit);
    }
}
