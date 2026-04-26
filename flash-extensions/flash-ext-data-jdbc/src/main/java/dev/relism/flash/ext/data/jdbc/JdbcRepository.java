package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.*;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public abstract class JdbcRepository<T, ID> extends Repository<T, ID> {

    private final String table;
    private final String idColumn;

    protected JdbcRepository(String table, String idColumn) {
        this.table    = table;
        this.idColumn = idColumn;
    }

    protected Connection connection() {
        return Tx.resource(Connection.class);
    }

    // ── Subclass contract ─────────────────────────────────────────────────────

    protected abstract T    mapRow(ResultSet rs)                        throws SQLException;
    protected abstract void bindInsert(PreparedStatement ps, T entity)  throws SQLException;
    protected abstract void bindUpdate(PreparedStatement ps, T entity)  throws SQLException;
    protected abstract String insertSql();
    protected abstract String updateSql();

    // ── Repository abstract impl ──────────────────────────────────────────────

    @Override
    protected Optional<T> doFindById(ID id) {
        return queryOne("select * from " + table + " where " + idColumn + " = ?",
                ps -> ps.setObject(1, id));
    }

    @Override
    protected List<T> doFindAll() {
        return queryMany("select * from " + table, ps -> {});
    }

    @Override
    protected List<T> doFindAll(int page, int size) {
        return queryMany("select * from " + table + " limit ? offset ?", ps -> {
            ps.setInt(1, size);
            ps.setInt(2, page * size);
        });
    }

    @Override
    protected List<T> doFindAll(Sort sort) {
        return queryMany("select * from " + table + orderClause(sort), ps -> {});
    }

    @Override
    protected List<T> doFindAll(int page, int size, Sort sort) {
        return queryMany("select * from " + table + orderClause(sort) + " limit ? offset ?",
                ps -> {
                    ps.setInt(1, size);
                    ps.setInt(2, page * size);
                });
    }

    @Override
    protected Page<T> doFindPage(int page, int size) {
        long total = doCount();
        return new Page<>(doFindAll(page, size), page, size, total);
    }

    @Override
    protected Page<T> doFindPage(int page, int size, Sort sort) {
        long total = doCount();
        return new Page<>(doFindAll(page, size, sort), page, size, total);
    }

    @Override
    protected T doSave(T entity) {
        try (PreparedStatement ps = connection().prepareStatement(
                insertSql(), Statement.RETURN_GENERATED_KEYS)) {
            bindInsert(ps, entity);
            ps.executeUpdate();
            applyGeneratedKey(ps, entity);
            return entity;
        } catch (SQLException e) { throw new TxException(e); }
    }

    @Override
    protected T doUpdate(T entity) {
        try (PreparedStatement ps = connection().prepareStatement(updateSql())) {
            bindUpdate(ps, entity);
            ps.executeUpdate();
            return entity;
        } catch (SQLException e) { throw new TxException(e); }
    }

    @Override
    protected void doDelete(T entity) {
        throw new UnsupportedOperationException("Override doDelete() or use deleteById()");
    }

    @Override
    protected void doDeleteById(ID id) {
        mutate("delete from " + table + " where " + idColumn + " = ?",
                ps -> ps.setObject(1, id));
    }

    @Override
    protected boolean doExistsById(ID id) {
        return queryOne("select 1 from " + table + " where " + idColumn + " = ?",
                ps -> ps.setObject(1, id),
                rs -> rs.getInt(1)).isPresent();
    }

    @Override
    protected long doCount() {
        return queryOne("select count(*) from " + table, ps -> {},
                rs -> rs.getLong(1)).orElse(0L);
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    protected Optional<T> queryOne(String sql, SqlBinder params) {
        List<T> r = queryMany(sql, params);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    protected <R> Optional<R> queryOne(String sql, SqlBinder params,
                                       SqlMapper<R> mapper) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            params.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw new TxException(e); }
    }

    protected List<T> queryMany(String sql, SqlBinder params) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            params.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rs.next()) results.add(mapRow(rs));
                return results;
            }
        } catch (SQLException e) { throw new TxException(e); }
    }

    protected int mutate(String sql, SqlBinder params) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            params.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new TxException(e); }
    }

    protected void applyGeneratedKey(PreparedStatement ps, T entity) throws SQLException {
        // override when entity has a generated PK
    }

    private String orderClause(Sort sort) {
        return " order by " + sort.columns().stream()
                .map(c -> c.column() + (c.asc() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    @FunctionalInterface public interface SqlBinder { void bind(PreparedStatement ps) throws SQLException; }
    @FunctionalInterface public interface SqlMapper<R> { R map(ResultSet rs) throws SQLException; }
}