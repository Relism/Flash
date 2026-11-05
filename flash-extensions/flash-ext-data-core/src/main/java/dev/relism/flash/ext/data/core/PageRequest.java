package dev.relism.flash.ext.data.core;

import dev.relism.flash.ext.serde.FromRequest;

import java.util.List;
import java.util.function.Function;

/**
 * The page a client asked for: how many items, in which order, after which one. Made by
 * {@link Pager#read} from {@code ?size=&sort=&after=}, already checked, and answered by
 * {@link Repository#findPage(PageRequest)}. Like the request it was read from, it belongs to the
 * request being handled.
 *
 * <p>{@code @FromRequest} tells flash-ext-serde that a handler takes it from the query, never from
 * the body; without serde the annotation is not loaded.
 */
@FromRequest
public final class PageRequest<T> {

    final Pager<T> pager;
    final int size;
    /** The key sorted by and the direction: {@code key << 1 | desc}. Key 0 is the id. */
    final int sort;
    /** The sort value and the id of the last item of the previous page, or null on the first page. */
    final Object[] after;

    PageRequest(Pager<T> pager, int size, int sort, Object[] after) {
        this.pager = pager;
        this.size = size;
        this.sort = sort;
        this.after = after;
    }

    /**
     * At most {@code size} of {@code rows}, read with one more than that, and the token of the page
     * after, from the values {@code loaded} gives for the last row.
     */
    Page<T> page(List<T> rows, Function<? super T, Object> loaded) {
        if (rows.size() <= size) return new Page<>(rows, null);
        List<T> content = rows.subList(0, size);
        return new Page<>(content, pager.token(sort, loaded.apply(content.get(size - 1))));
    }
}
