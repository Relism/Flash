# Using Hibernate

## HibernateTxManager

`new HibernateTxManager(sessionFactory)` opens one `Session` per transaction. It implements the
propagation rules of [transactions](../../flash-ext-data-core/docs/transactions.md): `REQUIRES_NEW`
and `NOT_SUPPORTED` suspend the current session and restore it afterwards. `SUPPORTS` with no
transaction to join opens one, since a `Session` reads inside a transaction: repository reads and
`tx.read` cost a `BEGIN` and a `COMMIT` here, unlike with JDBC.

A new transaction begins a Hibernate transaction, sets the session read-only by default when the
definition is read-only, and applies a non-default isolation level through the connection. Inside a
transaction, `tx.resource(Session.class)` is the session in use.

`close()` closes the session factory and then the `DataSource` it was given, when that is
`AutoCloseable`. Hibernate stops a pool it built itself but never one handed to it.

## Repositories

`HibernateData.create(manager)` returns a `Data` whose `repository(Class)` creates a
`HibernateRepository` for the entity. It supports every `Repository` operation:

| Operation | Implementation |
|---|---|
| `findById` | `session.get` |
| `findAll(query)` | HQL `from <EntitySimpleName> [where <spec>] [order by <sort>]` with named parameters `:p1`, `:p2`, with `setFirstResult` and `setMaxResults` for pages |
| `findPage` | The query plus a `select count(*)` with the same condition |
| `save` | `persist` |
| `saveAll` | `persist` each, with a flush and clear every 50 entities |
| `update` | `merge`, returning the managed entity |
| `delete` | `remove` (merging first if detached) |
| `deleteById` | Loads the entity, then removes it |
| `deleteAll(spec)` | HQL `delete from <EntitySimpleName> where <spec>` |

The entity is referred to by its simple class name in HQL, and the columns in a `Spec` or `Sort` are
entity property names. `updateAll` throws `UnsupportedOperationException` unless overridden.

Property names and sort columns are written into the HQL as given: do not pass user input.

## Custom queries

Extend `HibernateRepository<T, ID>` for HQL of your own:

```java
final class Users extends HibernateRepository<User, Long> {
    Users(Tx tx) { super(tx, User.class); }

    List<User> byDomain(String domain) {
        return hql("from User where email like :d", q -> q.setParameter("d", "%@" + domain));
    }
    int deactivate(String domain) {
        return hqlMutate("update User set active = false where email like :d", q -> q.setParameter("d", "%@" + domain));
    }
}
```

| Method | Purpose |
|---|---|
| `session()` | The transaction's `Session` |
| `hql(hql, params)`, `hql(hql, resultType, params)` | A read-only query |
| `hqlMutate(hql, params)` | A mutation in a read-write transaction, returning the count |

Instantiate it with `new Users(new Tx(manager))`, or pass a `RepositoryFactory` to `new Data(...)`.
