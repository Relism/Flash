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
    protected abstract void bindInsert(PreparedStatement ps, T entity) throws SQLException;
    protected abstract void bindUpdate(PreparedStatement ps, T entity) throws SQLException;
    protected abstract String insertSql();
    protected abstract String updateSql();

    @Override
    protected Optional<T> doFindById(ID id) {
        return queryOne("select * from " + table + " where " + idColumn + " = ?", ps -> ps.setObject(1, id));
    }

    @Override
    protected List<T> doFind(Query<T> query) {
        JdbcSpecContext ctx = new JdbcSpecContext();
        String where = query.spec() != null ? " where " + query.spec().toFragment(ctx) : "";
        String order = query.sort() != null && query.sort().isSorted() ? " order by " + orderClause(query.sort()) : "";
        String paging = query.isPaged() ? " limit ? offset ?" : "";

        return queryMany("select * from " + table + where + order + paging, ps -> {
            if (query.isPaged()) {
                ctx.applyParameters(ps);
                int base = ctx.size();
                ps.setInt(base + 1, query.size());
                ps.setInt(base + 2, query.page() * query.size());
                return;
            }
            ctx.applyParameters(ps);
        });
    }

    @Override
    protected Optional<T> doFindOne(Spec<T> spec) {
        return doFind(Query.<T>all().where(spec).page(0, 1)).stream().findFirst();
    }

    @Override
    protected Page<T> doFindPage(Query<T> query) {
        if (!query.isPaged()) {
            throw new IllegalArgumentException("Paged query requires page and size");
        }
        long total = countWhere(query.spec());
        List<T> content = doFind(query);
        return new Page<>(content, query.page(), query.size(), total);
    }

    @Override
    protected boolean doExistsById(ID id) {
        return queryOne("select 1 from " + table + " where " + idColumn + " = ?", ps -> ps.setObject(1, id), rs -> rs.getInt(1)).isPresent();
    }

    @Override
    protected long doCount() {
        return queryOne("select count(*) from " + table, ps -> {}, rs -> rs.getLong(1)).orElse(0L);
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

    protected Class<T> entityType() {
        return null;
    }

    private long countWhere(Spec<T> spec) {
        JdbcSpecContext ctx = new JdbcSpecContext();
        String where = spec != null ? " where " + spec.toFragment(ctx) : "";
        return queryOne("select count(*) from " + table + where, ctx::applyParameters, rs -> rs.getLong(1)).orElse(0L);
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
