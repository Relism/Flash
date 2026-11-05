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

`ResourceTxManager<R>` implements it over one resource per transaction, a connection or a session:
the propagation rules, suspension and resumption, and the synchronizations. A backend extends it and
says what to do with its resource:

| Method | Does |
|---|---|
| `acquire()` | A new resource, outside any transaction |
| `begin(resource, definition)` | Starts a transaction on it, read-only and isolated as the definition says |
| `commitResource`, `rollbackResource` | Ends it |
| `release(resource)` | Closes it. `discard(resource)` does the same and ignores a failure |
| `acquireForReads()` | For `SUPPORTS` with no transaction to join: a resource that reads outside any, shared by the reads nested in it. `null` by default, which opens a transaction instead |
| `close()` | Releases the pool or factory |

A checked exception from a hook is wrapped in `TxException`; an unchecked one leaves as it was
thrown. `TxStatus.resource(Class)` is the resource of the transaction in use.

A manager written from scratch binds its state with `ResourceRegistry.bind(key, value)` on the
current thread, with a `TxResourceKey` per manager instance so two managers on one thread never join
each other, and fires the synchronizations with `ResourceRegistry.fireBeforeCommit` and
`fireSynchronizations` from the baseline captured at `begin` (`ResourceRegistry.synchronizationCount()`).

## Repository

`Repository<T, ID>` is abstract. A backend implements the `do*` methods (`doFindById`, `doFind`,
`doExistsById`, `doCount(spec)`, `doSave`, `doSaveAll`, `doUpdate`, `doDelete`, `doDeleteById`,
`doDeleteAll`, `doUpdateAll`) and gets transaction handling from the base class. Use
`tx().resource(...)` to get the connection or session, and implement a `SpecContext` whose `bind`
returns the placeholder for a value (`?`, `:p1`).

`findOne` and `findPage` are built on `doFind`, which must honour `Query.limit()`. `findPage` asks
for `size + 1` rows with a `Spec` and a `Sort` of its own, using `is null`, `is not null`, `=`, `<`,
`>`, `and` and `or`. Its first page also runs `doFind` once with every sortable column in the order
and a condition that matches nothing, so a wrong column fails there, whichever sort it belongs to. The columns come from `column(property)`, which returns the property's name
unless the backend or the application overrides it. The token of a page reads the last row through
`loaded(entity)`, the entity itself by default; a backend that hands out proxies returns the instance
behind one, as Hibernate does with `Hibernate.unproxy`.

A `RepositoryFactory` creates one repository for an entity type:

```java
RepositoryFactory factory = new RepositoryFactory() {
    public <T, ID extends Serializable> Repository<T, ID> create(Tx tx, Class<T> type) { ... }
};
Data data = new Data(new Tx(manager), factory);
```
