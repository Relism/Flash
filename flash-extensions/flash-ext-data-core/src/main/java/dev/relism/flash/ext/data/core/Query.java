package dev.relism.flash.ext.data.core;

public record Query<T>(Spec<T> spec, Sort sort, Integer page, Integer size) {
    public Query {
        spec = spec == null ? Spec.all() : spec;
        sort = sort == null ? Sort.unsorted() : sort;
    }

    public static <T> Query<T> all() {
        return new Query<>(Spec.all(), Sort.unsorted(), null, null);
    }

    public Query<T> where(Spec<T> spec) {
        return new Query<>(spec, sort, page, size);
    }

    public Query<T> orderBy(Sort sort) {
        return new Query<>(spec, sort, page, size);
    }

    public Query<T> page(int page, int size) {
        return new Query<>(spec, sort, page, size);
    }

    public boolean isPaged() {
        return page != null && size != null;
    }
}
