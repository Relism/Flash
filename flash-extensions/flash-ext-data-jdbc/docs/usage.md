# Using JDBC

## JdbcTxManager

`new JdbcTxManager(dataSource)` implements the propagation rules of
[transactions](../../flash-ext-data-core/docs/transactions.md) over one connection per transaction:

- A new transaction takes a connection, sets auto-commit off, applies `readOnly` and the isolation
  level (unless `DEFAULT`).
- `REQUIRES_NEW` and `NOT_SUPPORTED` suspend the current transaction's connection and restore it
  afterwards.
- `SUPPORTS` with no transaction to join takes one connection in auto-commit for its scope, shared by
  every `SUPPORTS` nested in it: no `BEGIN` and no `COMMIT`. Repository reads and `tx.read` run this
  way, so a lookup costs one round trip. A `REQUIRED` nested in it opens a transaction on a second
  connection.
- `close()` closes the `DataSource` when it is `AutoCloseable`, as a connection pool is.

Inside a transaction, `tx.resource(Connection.class)` is the connection in use.

## JdbcRepository

Extend `JdbcRepository<T, ID>(tx, table, idColumn)` and implement `mapRow(ResultSet)`. The rest is
optional:

| Method | Purpose |
|---|---|
| `columns()` | What `findById` and `findAll` select. Default `*` |
| `insertSql()`, `bindInsert(ps, entity)` | Insert statement and its parameters, for `save`. Without them `save` throws `UnsupportedOperationException` |
| `updateSql()`, `bindUpdate(ps, entity)` | Update statement and its parameters, for `update`. Same rule |
| `applyGeneratedKey(ps, entity)` | Copy a generated key into the entity. Insert statements are prepared with `RETURN_GENERATED_KEYS` |

The base class implements the rest as SQL against `table`:

| Operation | SQL |
|---|---|
| `findById`, `existsById` | `select <columns> ... where <idColumn> = ?`, `select 1 ...` |
| `findAll(query)` | `select <columns> from <table> [where <spec>] [order by <sort>] [limit ? offset ?]` |
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
| `connection()` | The connection in use: the transaction's, or the `SUPPORTS` scope's |
| `queryOne(sql, binder)`, `queryOne(sql, binder, mapper)` | One row as `Optional` |
| `queryMany(sql, binder)` | All rows mapped by `mapRow` |
| `mutate(sql, binder)` | Execute an update, return the count |

`SqlBinder` binds parameters on a `PreparedStatement`; `SqlMapper<R>` maps a `ResultSet` row. Call them
from your own repository methods, wrapped in `roQuery` (joins, or runs as `SUPPORTS`) or `rwQuery`
(joins, or opens a read-write transaction).

Column names used in a `Spec` or `Sort` are written into the SQL as given: do not pass user input.
