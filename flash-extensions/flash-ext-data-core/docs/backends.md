# Writing a backend

A backend provides a `TxManager`, a `Repository` subclass and a `RepositoryFactory`.

## TxManager

```java
public interface TxManager extends AutoCloseable {
    TxStatus begin(TxDefinition definition);
    void commit(TxStatus status);
    void rollback(TxStatus status);
    void close();
}
```

`begin` implements the propagation rules and binds the transaction's resource with
`ResourceRegistry.bind(key, status)` on the current thread. Use a `TxResourceKey` per manager
instance so two managers on one thread never join each other. `commit` and `rollback` fire the
synchronizations with `ResourceRegistry.fireBeforeCommit` and `fireSynchronizations`, from the
baseline captured at `begin` (`ResourceRegistry.synchronizationCount()`), and unbind the resource
before the after-completion callbacks run. `close` releases the pool or factory.

`TxStatus` reports `isNewTransaction()`, `isReadOnly()`, `isRollbackOnly()`, `markRollbackOnly()`
and `resource(Class)`.

## Repository

`Repository<T, ID>` is abstract. A backend implements the `do*` methods (`doFindById`, `doFind`,
`doFindOne`, `doFindPage`, `doExistsById`, `doCount`, `doSave`, `doSaveAll`, `doUpdate`, `doDelete`,
`doDeleteById`, `doDeleteAll`, `doUpdateAll`) and gets transaction handling from the base class. Use
`tx().resource(...)` to get the connection or session, and implement a `SpecContext` whose `bind`
returns the placeholder for a value (`?`, `:p1`).

A `RepositoryFactory` creates one repository for an entity type:

```java
RepositoryFactory factory = new RepositoryFactory() {
    public <T, ID extends Serializable> Repository<T, ID> create(Tx tx, Class<T> type) { ... }
};
Data data = new Data(new Tx(manager), factory);
```
