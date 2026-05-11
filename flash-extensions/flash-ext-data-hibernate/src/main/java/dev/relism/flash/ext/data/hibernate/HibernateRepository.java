package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.*;
import jakarta.persistence.TypedQuery;
import org.hibernate.Session;
import org.hibernate.query.MutationQuery;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class HibernateRepository<T, ID extends Serializable> extends Repository<T, ID> {

    private final Class<T> type;

    protected HibernateRepository(Tx tx, Class<T> type) {
        super(tx);
        this.type = type;
    }

    protected Session session() {
        return tx().resource(Session.class);
    }

    @Override
    protected Optional<T> doFindById(ID id) {
        return Optional.ofNullable(session().get(type, id));
    }

    @Override
    protected List<T> doFind(Query<T> query) {
        HibernateSpecContext ctx = new HibernateSpecContext();
        String where = query.spec() != null ? " where " + query.spec().toFragment(ctx) : "";
        String order = query.sort() != null && query.sort().isSorted() ? " order by " + orderClause(query.sort()) : "";

        TypedQuery<T> q = session().createQuery("from " + type.getSimpleName() + where + order, type);
        ctx.applyParameters(q);

        if (query.isPaged()) {
            q.setFirstResult(query.page() * query.size());
            q.setMaxResults(query.size());
        }
        return q.getResultList();
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
        return doFindById(id).isPresent();
    }

    @Override
    protected long doCount() {
        return countWhere(Spec.all());
    }

    @Override
    protected T doSave(T entity) {
        session().persist(entity);
        return entity;
    }

    @Override
    protected List<T> doSaveAll(Iterable<T> entities) {
        List<T> saved = new ArrayList<>();
        Session s = session();
        int i = 0;
        for (T entity : entities) {
            s.persist(entity);
            saved.add(entity);
            if (++i % 50 == 0) {
                s.flush();
                s.clear();
            }
        }
        return saved;
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
    protected int doDeleteAll(Spec<T> spec) {
        HibernateSpecContext ctx = new HibernateSpecContext();
        String where = " where " + spec.toFragment(ctx);
        MutationQuery q = session().createMutationQuery("delete from " + type.getSimpleName() + where);
        ctx.applyParameters(q);
        return q.executeUpdate();
    }

    @Override
    protected int doUpdateAll(Spec<T> spec, T patch) {
        throw new UnsupportedOperationException("Override doUpdateAll() for bulk UPDATE support");
    }

    protected List<T> hql(String hql, Consumer<TypedQuery<T>> params) {
        return roQuery(() -> {
            TypedQuery<T> q = session().createQuery(hql, type);
            params.accept(q);
            return q.getResultList();
        });
    }

    protected <R> List<R> hql(String hql, Class<R> resultType, Consumer<TypedQuery<R>> params) {
        return roQuery(() -> {
            TypedQuery<R> q = session().createQuery(hql, resultType);
            params.accept(q);
            return q.getResultList();
        });
    }

    protected int hqlMutate(String hql, Consumer<MutationQuery> params) {
        return rwQuery(() -> {
            MutationQuery q = session().createMutationQuery(hql);
            params.accept(q);
            return q.executeUpdate();
        });
    }

    protected Class<T> entityType() {
        return type;
    }

    private long countWhere(Spec<T> spec) {
        HibernateSpecContext ctx = new HibernateSpecContext();
        String where = spec != null ? " where " + spec.toFragment(ctx) : "";
        TypedQuery<Long> q = session().createQuery("select count(*) from " + type.getSimpleName() + where, Long.class);
        ctx.applyParameters(q);
        return q.getResultStream().findFirst().orElse(0L);
    }

    private String orderClause(Sort sort) {
        return sort.columns().stream()
                .map(c -> c.column() + (c.asc() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }
}
