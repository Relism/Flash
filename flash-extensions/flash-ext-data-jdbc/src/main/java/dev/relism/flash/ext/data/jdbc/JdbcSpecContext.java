package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.SpecContext;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
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
            // JDBC 4.2 requires the driver to take an OffsetDateTime, not an Instant (PgJDBC refuses one).
            Object value = params.get(i);
            ps.setObject(i + 1, value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value);
        }
    }
}
