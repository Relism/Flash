package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.SpecContext;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class JdbcSpecContext implements SpecContext {
    private final List<Object> params = new ArrayList<>();

    @Override
    public String bind(Object value) {
        params.add(value);
        return "?";
    }

    void applyParameters(PreparedStatement ps) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    int size() {
        return params.size();
    }
}
