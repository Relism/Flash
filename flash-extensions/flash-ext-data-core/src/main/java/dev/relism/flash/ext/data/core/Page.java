package dev.relism.flash.ext.data.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One page of a list, and where the next one starts: {@code next} is what a client sends back as
 * {@code after}, and {@code null} on the last page.
 */
public record Page<T>(List<T> content, String next) {

    public Page {
        Objects.requireNonNull(content, "content");
    }

    /** The same page, each item mapped: an entity page answered as views. */
    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = new ArrayList<>(content.size());
        for (T item : content) mapped.add(mapper.apply(item));
        return new Page<>(mapped, next);
    }
}
