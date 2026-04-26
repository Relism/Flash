package dev.relism.flash.ext.data.core;

import java.util.ArrayList;
import java.util.List;

public record Sort(List<Column> columns) {

    public record Column(String column, boolean asc) {}

    public static Sort by(String column)          { return new Sort(List.of(new Column(column, true))); }
    public static Sort desc(String column)        { return new Sort(List.of(new Column(column, false))); }
    public static Sort by(String col, boolean asc){ return new Sort(List.of(new Column(col, asc))); }

    public Sort then(String column)               { return thenBy(column, true); }
    public Sort thenDesc(String column)           { return thenBy(column, false); }

    private Sort thenBy(String column, boolean asc) {
        List<Column> next = new ArrayList<>(columns);
        next.add(new Column(column, asc));
        return new Sort(next);
    }
}