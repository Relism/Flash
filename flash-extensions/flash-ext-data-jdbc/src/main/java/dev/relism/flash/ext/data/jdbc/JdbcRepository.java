package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class JdbcRepository<T, ID> extends Repository<T, ID> {

    private final String table;
    private final String idColumn;

    protected JdbcRepository(Tx tx, String table, String idColumn) {
        super(tx);
        this.table = table;
        this.idColumn = idColumn;
    }

    protected Connection connection() {
        return tx().resource(Connection.class);
    }

    protected abstract T mapRow(ResultSet rs) throws SQLException;

    /** What {@code findById} and {@code findAll} select: every column unless overridden. */
    protected String columns() {
        return "*";
    }

    /** For {@code save}: override with {@link #bindInsert}. */
    protected String insertSql() {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not override insertSql()");
    }

    protected void bindInsert(PreparedStatement ps, T entity) throws SQLException {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not override bindInsert()");
    }

    /** For {@code update}: override with {@link #bindUpdate}. */
    protected String updateSql() {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not override updateSql()");
    }

    protected void bindUpdate(PreparedStatement ps, T entity) throws SQLException {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not override bindUpdate()");
    }

    @Override
    protected Optional<T> doFindById(ID id) {
        return queryOne("select " + columns() + " from " + table + " where " + idColumn + " = ?", ps -> ps.setObject(1, id));
    }

    /** A limit is a row limit of JDBC itself ({@code setMaxRows}), which every driver honours. */
    @Override
    protected List<T> doFind(Query<T> query) {
        JdbcSpecContext ctx = new JdbcSpecContext();
        String order = query.sort().isSorted() ? " order by " + orderClause(query.sort()) : "";
        return queryMany("select " + columns() + " from " + table + " where " + query.spec().toFragment(ctx) + order, ps -> {
            ctx.applyParameters(ps);
            ps.setMaxRows(query.limit());
        });
    }

    @Override
    protected boolean doExistsById(ID id) {
        return queryOne("select 1 from " + table + " where " + idColumn + " = ?", ps -> ps.setObject(1, id), rs -> rs.getInt(1)).isPresent();
    }

    @Override
    protected long doCount(Spec<T> spec) {
        JdbcSpecContext ctx = new JdbcSpecContext();
        return queryOne("select count(*) from " + table + " where " + spec.toFragment(ctx), ctx::applyParameters,
                rs -> rs.getLong(1)).orElse(0L);
    }

    @Override
    protected T doSave(T entity) {
        try (PreparedStatement ps = connection().prepareStatement(insertSql(), Statement.RETURN_GENERATED_KEYS)) {
            bindInsert(ps, entity);
            ps.executeUpdate();
            applyGeneratedKey(ps, entity);
            return entity;
        } catch (SQLException e) {
            throw new TxException(e);
        }
    }

    @Override
    protected List<T> doSaveAll(Iterable<T> entities) {
        List<T> saved = new ArrayList<>();
        for (T entity : entities) {
            saved.add(doSave(entity));
        }
        return saved;
    }

    @Override
    protected T doUpdate(T entity) {
        try (PreparedStatement ps = connection().prepareStatement(updateSql())) {
            bindUpdate(ps, entity);
            ps.executeUpdate();
            return entity;
        } catch (SQLException e) {
            throw new TxException(e);
        }
    }

    @Override
    protected void doDelete(T entity) {
        throw new UnsupportedOperationException("Override doDelete() or use deleteById()");
    }

    @Override
    protected void doDeleteById(ID id) {
        mutate("delete from " + table + " where " + idColumn + " = ?", ps -> ps.setObject(1, id));
    }

    @Override
    protected int doDeleteAll(Spec<T> spec) {
        JdbcSpecContext ctx = new JdbcSpecContext();
        String where = " where " + spec.toFragment(ctx);
        return mutate("delete from " + table + where, ctx::applyParameters);
    }

    @Override
    protected int doUpdateAll(Spec<T> spec, T patch) {
        throw new UnsupportedOperationException("Override doUpdateAll() for bulk UPDATE support");
    }

    protected Optional<T> queryOne(String sql, SqlBinder params) {
        List<T> r = queryMany(sql, params);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    protected <R> Optional<R> queryOne(String sql, SqlBinder params, SqlMapper<R> mapper) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            params.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TxException(e);
        }
    }

    protected List<T> queryMany(String sql, SqlBinder params) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            params.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rs.next()) results.add(mapRow(rs));
                return results;
            }
        } catch (SQLException e) {
            throw new TxException(e);
        }
    }

    protected int mutate(String sql, SqlBinder params) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            params.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new TxException(e);
        }
    }

    protected void applyGeneratedKey(PreparedStatement ps, T entity) throws SQLException {
        // override when entity has a generated PK
    }

    private String orderClause(Sort sort) {
        return sort.columns().stream()
                .map(c -> c.column() + (c.asc() ? " ASC" : " DESC"))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    @FunctionalInterface
    public interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlMapper<R> {
        R map(ResultSet rs) throws SQLException;
    }
}
