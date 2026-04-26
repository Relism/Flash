package dev.relism.flash.ext.data.core;

import java.util.List;

public record Page<T>(
        List<T> content,
        int     page,
        int     size,
        long    total
) {
    public int  totalPages()   { return size == 0 ? 0 : (int) Math.ceil((double) total / size); }
    public boolean hasNext()   { return page + 1 < totalPages(); }
    public boolean hasPrev()   { return page > 0; }
    public boolean isEmpty()   { return content.isEmpty(); }
}