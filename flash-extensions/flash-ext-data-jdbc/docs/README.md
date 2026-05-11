# flash-ext-data-jdbc

Backend JDBC per `flash-ext-data-core`.

## Scopo

Questo modulo implementa `TxManager` sopra `DataSource` e fornisce una base repository SQL raw.

## Come si usa

### 1. Creare il manager

```java
DataSource dataSource = ...;
JdbcTxManager txManager = new JdbcTxManager(dataSource);
DataExtension extension = new DataExtension(txManager);
```

### 2. Installare l’estensione in Flash

Come per Hibernate, `DataExtension` registra `Tx` nel `FlashContext` e abilita `@Transactional` sugli handler class-based.

### 3. Definire una repository

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

Anche qui puoi esporre `Spec` riusabili e comporre query dal service layer:

```java
public final class UserRepository extends JdbcRepository<User, Long> {
    public static final SpecBuilder.FieldSpec<User, String> EMAIL = SpecBuilder.field("email");

    public UserRepository(Tx tx) {
        super(tx, "users", "id");
    }
}
```

Per il salvataggio e l’update devi fornire il binding esplicito:

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

## Come funziona sotto

- La tx corrente espone una `Connection`.
- `Tx.resource(Connection.class)` recupera la connessione bindata al thread.
- `REQUIRES_NEW` sospende la connessione attiva e ne apre una nuova.
- `NOT_SUPPORTED` sospende il contesto e prosegue senza tx.

## Repository base

`JdbcRepository` fornisce:

- query `select` con `queryOne`, `queryMany`
- mutation con `mutate`
- persistenza con `doSave`, `doUpdate`
- paging con `doFindPage`
- bulk `deleteAll(Spec<T>)`
- helper raw `queryOne(...)`, `queryMany(...)`, `mutate(...)`

Le repository concrete devono solo tradurre tra `ResultSet` e dominio.

## Semantica transazionale

- `REQUIRED`: join o apertura nuova tx.
- `REQUIRES_NEW`: sospensione del contesto corrente.
- `SUPPORTS`: join se c’è tx, altrimenti no-op.
- `NOT_SUPPORTED`: sospende e prosegue senza tx.
- `MANDATORY`: fallisce se non c’è tx.

## Note

- La `Connection` viene chiusa a fine tx nuova.
- Le synchronizations vengono eseguite al commit/rollback.
- Se una repository usa `doDelete(T)`, il comportamento predefinito è non supportato: usare `deleteById` o override specifico.
