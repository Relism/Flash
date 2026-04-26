package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.*;
import org.hibernate.Session;
import org.hibernate.query.MutationQuery;

import jakarta.persistence.TypedQuery;
import java.io.Serializable;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Hibernate-backed repository base.
 * Never extend this directly — extend {@link Repository} from the core.
 * This class is instantiated internally by flash-ext-data-hibernate.
 */
public abstract class HibernateRepository<T, ID extends Serializable>
        extends Repository<T, ID> {

    private final Class<T> type;

    protected HibernateRepository(Class<T> type) {
        this.type = type;
    }

    // ── Session — always safe, tx() wrapper guarantees active transaction ─────

    protected Session session() {
        return Tx.resource(Session.class);
    }

    // ── Repository abstract impl ──────────────────────────────────────────────

    @Override
    protected Optional<T> doFindById(ID id) {
        return Optional.ofNullable(session().get(type, id));
    }

    @Override
    protected List<T> doFindAll() {
        return hql("from " + type.getSimpleName()).getResultList();
    }

    @Override
    protected List<T> doFindAll(int page, int size) {
        return hql("from " + type.getSimpleName())
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
    }

    @Override
    protected List<T> doFindAll(Sort sort) {
        return hql("from " + type.getSimpleName() + orderClause(sort))
                .getResultList();
    }

    @Override
    protected List<T> doFindAll(int page, int size, Sort sort) {
        return hql("from " + type.getSimpleName() + orderClause(sort))
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
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
        session().persist(entity);
        return entity;
    }

    @Override
    protected T doUpdate(T entity) {
        return session().merge(entity);
    }

    @Override
    protected void doDelete(T entity) {
        Session s = session();
        s.remove(s.contains(entity) ? entity : s.merge(entity));
    }

    @Override
    protected void doDeleteById(ID id) {
        doFindById(id).ifPresent(this::doDelete);
    }

    @Override
    protected boolean doExistsById(ID id) {
        return doFindById(id).isPresent();
    }

    @Override
    protected long doCount() {
        return session()
                .createQuery("select count(*) from " + type.getSimpleName(), Long.class)
                .uniqueResultOptional()
                .orElse(0L);
    }

    // ── Query helpers — usabili nelle sottoclassi domain ─────────────────────

    protected TypedQuery<T> hql(String hql) {
        return session().createQuery(hql, type);
    }

    protected <R> TypedQuery<R> hql(String hql, Class<R> resultType) {
        return session().createQuery(hql, resultType);
    }

    protected Optional<T> findOne(String hql, Consumer<TypedQuery<T>> params) {
        TypedQuery<T> q = hql(hql);
        params.accept(q);
        return q.getResultStream().findFirst();
    }

    protected List<T> findMany(String hql, Consumer<TypedQuery<T>> params) {
        return tx(() -> {
            TypedQuery<T> q = hql(hql);
            params.accept(q);
            return q.getResultList();
        });
    }

    protected List<T> findMany(String hql, Consumer<TypedQuery<T>> params,
                               int page, int size) {
        return tx(() -> {
            TypedQuery<T> q = hql(hql);
            params.accept(q);
            return q.setFirstResult(page * size).setMaxResults(size).getResultList();
        });
    }

    protected Page<T> findManyPaged(String hql, String countHql,
                                    Consumer<TypedQuery<T>> params,
                                    int page, int size) {
        return tx(() -> {
            long total = session()
                    .createQuery(countHql, Long.class)
                    .uniqueResultOptional()
                    .orElse(0L);
            List<T> content = findMany(hql, params, page, size);
            return new Page<>(content, page, size, total);
        });
    }

    protected int execute(String hql, Consumer<MutationQuery> params) {
        return tx(() -> {
            MutationQuery q = session().createMutationQuery(hql);
            params.accept(q);
            return q.executeUpdate();
        });
    }

    protected Class<T> entityType() {
        return type;
    }

    private String orderClause(Sort sort) {
        return " order by " + sort.columns().stream()
                .map(c -> c.column() + (c.asc() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }
}