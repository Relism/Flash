package dev.relism.flash.ext.data.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class Repository<T, ID> extends RepositorySupport<T, ID> {

    protected Repository(Tx tx) {
        super(tx);
    }

    public Optional<T> findById(ID id) {
        return roQuery(() -> doFindById(id));
    }

    public boolean existsById(ID id) {
        return roQuery(() -> doExistsById(id));
    }

    public long count() {
        return roQuery(this::doCount);
    }

    public List<T> findAll() {
        return findAll(Query.all());
    }

    public List<T> findAll(Spec<T> spec) {
        return findAll(Query.<T>all().where(spec));
    }

    public List<T> findAll(Query<T> query) {
        return roQuery(() -> doFind(query));
    }

    public Page<T> findPage(Query<T> query) {
        return roQuery(() -> doFindPage(query));
    }

    public Optional<T> findOne(Spec<T> spec) {
        return roQuery(() -> doFindOne(spec));
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

    public int deleteAll(Spec<T> spec) {
        return rwQuery(() -> doDeleteAll(spec));
    }

    public int updateAll(Spec<T> spec, T patch) {
        return rwQuery(() -> doUpdateAll(spec, patch));
    }

    public List<T> findAll(int page, int size) {
        return findAll(Query.<T>all().page(page, size));
    }

    public List<T> findAll(Sort sort) {
        return findAll(Query.<T>all().orderBy(sort));
    }

    public List<T> findAll(int page, int size, Sort sort) {
        return findAll(Query.<T>all().orderBy(sort).page(page, size));
    }

    public Page<T> findPage(int page, int size) {
        return findPage(Query.<T>all().page(page, size));
    }

    public Page<T> findPage(int page, int size, Sort sort) {
        return findPage(Query.<T>all().orderBy(sort).page(page, size));
    }

    public void deleteAll(Iterable<T> entities) {
        rwQuery(() -> {
            for (T entity : entities) {
                doDelete(entity);
            }
            return null;
        });
    }

    protected abstract Optional<T> doFindById(ID id);
    protected abstract List<T> doFind(Query<T> query);
    protected abstract Optional<T> doFindOne(Spec<T> spec);
    protected abstract Page<T> doFindPage(Query<T> query);
    protected abstract boolean doExistsById(ID id);
    protected abstract long doCount();
    protected abstract T doSave(T entity);
    protected abstract List<T> doSaveAll(Iterable<T> entities);
    protected abstract T doUpdate(T entity);
    protected abstract void doDelete(T entity);
    protected abstract void doDeleteById(ID id);
    protected abstract int doDeleteAll(Spec<T> spec);
    protected abstract int doUpdateAll(Spec<T> spec, T patch);
}
