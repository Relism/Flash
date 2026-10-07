# Using JDBC

## JdbcTxManager

`new JdbcTxManager(dataSource)` implements the propagation rules of
[transactions](../../flash-ext-data-core/docs/transactions.md) over one connection per transaction:

- A new transaction takes a connection, sets auto-commit off, applies `readOnly` and the isolation
  level (unless `DEFAULT`).
- `REQUIRES_NEW` and `NOT_SUPPORTED` suspend the current transaction's connection and restore it
  afterwards.
- `close()` closes the `DataSource` when it is `AutoCloseable`, as a connection pool is.

Inside a transaction, `tx.resource(Connection.class)` is the connection in use.

## JdbcRepository

Extend `JdbcRepository<T, ID>(tx, table, idColumn)`. You supply:

| Method | Purpose |
|---|---|
| `mapRow(ResultSet)` | Row to entity |
| `insertSql()`, `bindInsert(ps, entity)` | Insert statement and its parameters |
| `updateSql()`, `bindUpdate(ps, entity)` | Update statement and its parameters |
| `applyGeneratedKey(ps, entity)` | Optional: copy a generated key into the entity. Insert statements are prepared with `RETURN_GENERATED_KEYS` |

The base class implements the rest as SQL against `table`:

| Operation | SQL |
|---|---|
| `findById`, `existsById` | `select * ... where <idColumn> = ?`, `select 1 ...` |
| `findAll(query)` | `select * from <table> [where <spec>] [order by <sort>] [limit ? offset ?]` |
| `findPage` | The query plus `select count(*)` with the same condition |
| `count` | `select count(*) from <table>` |
| `save`, `update` | `insertSql()`, `updateSql()` |
| `deleteById` | `delete from <table> where <idColumn> = ?` |
| `deleteAll(spec)` | `delete from <table> where <spec>` |

`delete(entity)` and `updateAll` throw `UnsupportedOperationException` until you override `doDelete` or
`doUpdateAll`. A `SQLException` is wrapped in `TxException`. The `limit ? offset ?` syntax needs a
database that accepts it.

## Helpers for your own queries

| Method | Purpose |
|---|---|
| `connection()` | The transaction's connection |
| `queryOne(sql, binder)`, `queryOne(sql, binder, mapper)` | One row as `Optional` |
| `queryMany(sql, binder)` | All rows mapped by `mapRow` |
| `mutate(sql, binder)` | Execute an update, return the count |

`SqlBinder` binds parameters on a `PreparedStatement`; `SqlMapper<R>` maps a `ResultSet` row. Call them
from your own repository methods, wrapped in `roQuery` or `rwQuery` so they run in a transaction.

Column names used in a `Spec` or `Sort` are written into the SQL as given: do not pass user input.
