package dev.relism.flash.ext.data.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The operations on one entity type, implemented by a backend through the {@code do*} methods. Every
 * operation joins the current transaction: a read with none runs as {@code SUPPORTS}, a write opens
 * a read-write one.
 */
public abstract class Repository<T, ID> {

    private static final TxDefinition RW = TxDefinition.DEFAULTS;
    private static final TxDefinition RO = RW.withPropagation(TransactionPropagation.SUPPORTS).asReadOnly();

    private final Tx tx;
    /** The columns and orders of each pager this repository has answered, worked out on its first page. */
    private final Map<Pager<?>, Plan> plans = new ConcurrentHashMap<>();

    protected Repository(Tx tx) {
        this.tx = tx;
    }

    public Optional<T> findById(ID id) {
        return roQuery(() -> doFindById(id));
    }

    public boolean existsById(ID id) {
        return roQuery(() -> doExistsById(id));
    }

    public long count() {
        return count(Spec.all());
    }

    /** Counts the entities matched by {@code spec}. */
    public long count(Spec<T> spec) {
        return roQuery(() -> doCount(spec));
    }

    public List<T> findAll() {
        return findAll(Query.all());
    }

    public List<T> findAll(Spec<T> spec) {
        return findAll(Query.<T>all().where(spec));
    }

    public List<T> findAll(Sort sort) {
        return findAll(Query.<T>all().orderBy(sort));
    }

    public List<T> findAll(Query<T> query) {
        return roQuery(() -> doFind(query));
    }

    /** Returns the first matching entity, or an empty result if there is none. */
    public Optional<T> findOne(Spec<T> spec) {
        return roQuery(() -> doFind(Query.<T>all().where(spec).limit(1)).stream().findFirst());
    }

    /** The page {@code request} asks for, in its order, after the item it names. */
    public Page<T> findPage(PageRequest<T> request) {
        return findPage(request, Spec.all());
    }

    /**
     * The same, among the entities {@code scope} matches: a tenant's, a user's. Apply the scope
     * on every request; the continuation token does not contain it.
     */
    public Page<T> findPage(PageRequest<T> request, Spec<T> scope) {
        return roQuery(() -> keyset(request, scope));
    }

    public T save(T entity) {
        return rwQuery(() -> doSave(entity));
    }

    public T update(T entity) {
        return rwQuery(() -> doUpdate(entity));
    }

    public List<T> saveAll(Iterable<T> entities) {
        return rwQuery(() -> doSaveAll(entities));
    }

    public void delete(T entity) {
        rwQuery(() -> {
            doDelete(entity);
            return null;
        });
    }

    public void deleteById(ID id) {
        rwQuery(() -> {
            doDeleteById(id);
            return null;
        });
    }

    public void deleteAll(Iterable<T> entities) {
        rwQuery(() -> {
            for (T entity : entities) doDelete(entity);
            return null;
        });
    }

    public int deleteAll(Spec<T> spec) {
        return rwQuery(() -> doDeleteAll(spec));
    }

    public int updateAll(Spec<T> spec, T patch) {
        return rwQuery(() -> doUpdateAll(spec, patch));
    }

    protected final Tx tx() {
        return tx;
    }

    /** {@code work} in the caller's transaction, or in none. */
    protected final <R> R roQuery(Tx.TxCallable<R> work) {
        return tx.call(RO, work);
    }

    /** {@code work} in the caller's transaction, or in a new read-write one. */
    protected final <R> R rwQuery(Tx.TxCallable<R> work) {
        return tx.call(RW, work);
    }

    /**
     * The column a property of the entity is stored in, as a {@code Spec} or a {@code Sort} names
     * it. The property's own name by default; a backend whose columns are named otherwise overrides it.
     */
    protected String column(String property) {
        return property;
    }

    /**
     * The object whose fields hold {@code entity}'s values, for the token of a page: the entity
     * itself, or for a backend that hands out proxies, the instance a proxy stands for.
     */
    protected Object loaded(T entity) {
        return entity;
    }

    /**
     * Keyset paging over {@link #doFind}: the rows after the last item of the previous page, in the
     * page's order with the id as the tie-breaker, one more than the page holds. Rows whose sort
     * value is null come after all the others, ordered by id, so a nullable column pages in two
     * runs: the values, then the nulls.
     */
    private Page<T> keyset(PageRequest<T> request, Spec<T> scope) {
        Plan plan = plans.computeIfAbsent(request.pager, Plan::new);
        int key = request.sort >> 1;
        boolean desc = (request.sort & 1) == 1;
        String field = plan.columns[key], id = plan.columns[0], after = desc ? " < " : " > ";
        Sort byId = plan.byId[desc ? 1 : 0];
        Object[] last = request.after;
        int rows = request.size + 1;

        List<T> found;
        if (key == 0) {
            found = find(last == null ? scope : scope.and(c -> id + after + c.bind(last[1])), byId, rows);
        } else if (last != null && last[0] == null) {   // already among the nulls
            found = find(scope.and(c -> field + " is null and " + id + after + c.bind(last[1])), byId, rows);
        } else {
            boolean nullable = request.pager.keys[key].nullable;
            Spec<T> where = nullable ? scope.and(c -> field + " is not null") : scope;
            if (last != null) where = where.and(c -> "(" + field + after + c.bind(last[0]) + " or (" + field + " = "
                    + c.bind(last[0]) + " and " + id + after + c.bind(last[1]) + "))");
            found = find(where, plan.ordered[request.sort], rows);
            if (nullable && found.size() < rows) {
                List<T> nulls = find(scope.and(c -> field + " is null"), byId, rows - found.size());
                if (!nulls.isEmpty()) {
                    found = new ArrayList<>(found);
                    found.addAll(nulls);
                }
            }
        }
        return request.page(found, this::loaded);
    }

    private List<T> find(Spec<T> where, Sort sort, int rows) {
        return doFind(new Query<>(where, sort, rows));
    }

    /**
     * One pager's keys as this repository's columns, and every order a page can ask for. Built on the
     * first page, with a query that names every column, so a wrong one fails there whichever sort it
     * belongs to.
     */
    private final class Plan {
        final String[] columns;
        /** By sort: the key in its direction, then the id in the same one. */
        final Sort[] ordered;
        /** By id ascending, then descending. */
        final Sort[] byId;

        Plan(Pager<?> pager) {
            columns = new String[pager.keys.length];
            List<Sort.Column> every = new ArrayList<>(columns.length);
            for (int k = 0; k < columns.length; k++) {
                columns[k] = column(pager.keys[k].property);
                every.add(new Sort.Column(columns[k], true));
            }
            ordered = new Sort[columns.length * 2];
            for (int sort = 0; sort < ordered.length; sort++) {
                boolean asc = (sort & 1) == 0;
                ordered[sort] = new Sort(List.of(new Sort.Column(columns[sort >> 1], asc), new Sort.Column(columns[0], asc)));
            }
            byId = new Sort[] {Sort.by(columns[0], true), Sort.by(columns[0], false)};
            doFind(new Query<>(Spec.none(), new Sort(every), 1));
        }
    }

    protected abstract Optional<T> doFindById(ID id);
    /** The rows of {@code query}; a limit above 0 caps how many are read. */
    protected abstract List<T> doFind(Query<T> query);
    protected abstract boolean doExistsById(ID id);
    protected abstract long doCount(Spec<T> spec);
    protected abstract T doSave(T entity);
    protected abstract List<T> doSaveAll(Iterable<T> entities);
    protected abstract T doUpdate(T entity);
    protected abstract void doDelete(T entity);
    protected abstract void doDeleteById(ID id);
    protected abstract int doDeleteAll(Spec<T> spec);
    protected abstract int doUpdateAll(Spec<T> spec, T patch);
}
