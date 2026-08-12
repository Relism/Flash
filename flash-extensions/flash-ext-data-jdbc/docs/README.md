# flash-ext-data-jdbc

JDBC backend for `flash-ext-data-core`.

## Purpose

This module implements `TxManager` on top of a `DataSource` and provides a raw-SQL repository base
class.

## How to use it

### 1. Create the manager

```java
DataSource dataSource = ...;
JdbcTxManager txManager = new JdbcTxManager(dataSource);
DataExtension extension = new DataExtension(txManager);
```

### 2. Install the extension in Flash

As with Hibernate, `DataExtension` registers `Tx` in the `FlashContext` and enables
`@Transactional` on class-based handlers.

### 3. Define a repository

```java
public final class UserRepository extends JdbcRepository<User, Long> {
    public UserRepository(Tx tx) {
        super(tx, "users", "id");
    }

    @Override
    protected User mapRow(ResultSet rs) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("name"));
    }
}
```

Here too you can expose reusable `Spec`s and compose queries from the service layer:

```java
public final class UserRepository extends JdbcRepository<User, Long> {
    public static final SpecBuilder.FieldSpec<User, String> EMAIL = SpecBuilder.field("email");

    public UserRepository(Tx tx) {
        super(tx, "users", "id");
    }
}
```

Saving and updating need an explicit binding:

```java
@Override
protected String insertSql() {
    return "insert into users(name) values(?)";
}

@Override
protected void bindInsert(PreparedStatement ps, User entity) throws SQLException {
    ps.setString(1, entity.name());
}
```

## How it works underneath

- The current transaction exposes a `Connection`.
- `Tx.resource(Connection.class)` retrieves the connection bound to the thread.
- `REQUIRES_NEW` suspends the active connection and opens a new one.
- `NOT_SUPPORTED` suspends the context and continues without a transaction.

## Repository base class

`JdbcRepository` provides:

- `select` queries through `queryOne`, `queryMany`
- mutations through `mutate`
- persistence through `doSave`, `doUpdate`
- paging through `doFindPage`
- bulk `deleteAll(Spec<T>)`
- raw helpers `queryOne(...)`, `queryMany(...)`, `mutate(...)`

Concrete repositories only have to translate between `ResultSet` and the domain.

## Transactional semantics

- `REQUIRED`: join, or open a new transaction.
- `REQUIRES_NEW`: suspend the current context.
- `SUPPORTS`: join if a transaction exists, otherwise no-op.
- `NOT_SUPPORTED`: suspend and continue without a transaction.
- `MANDATORY`: fail if there is no transaction.

## Notes

- The `Connection` is closed when a new transaction ends.
- Synchronizations registered in a transaction fire when *that* transaction completes:
  `beforeCommit` while it is still active and the `Connection` still bound, the post-completion
  hooks once it is unbound. See `flash-ext-data-core/docs/README.md` for the full contract.
- If a repository uses `doDelete(T)`, the default behaviour is unsupported: use `deleteById` or
  override it.
