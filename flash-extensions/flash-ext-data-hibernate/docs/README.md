# flash-ext-data-hibernate

Backend Hibernate per `flash-ext-data-core`.

## Scopo

Questo modulo implementa `TxManager` sopra `SessionFactory` e fornisce una base repository Hibernate-centric.

## Come si usa

### 1. Creare il manager

```java
SessionFactory sessionFactory = ...;
HibernateTxManager txManager = new HibernateTxManager(sessionFactory);
DataExtension extension = new DataExtension(txManager);
```

### 2. Installare l’estensione in Flash

L’estensione registra `Tx` e `TxManager` nel `FlashContext`.
Le handler class-based annotate con `@Transactional` vengono wrappate automaticamente.

### 3. Definire una repository

```java
public final class UserRepository extends HibernateRepository<User, Long> {
    public UserRepository(Tx tx) {
        super(tx, User.class);
    }
}
```

Con il nuovo modello query/spec puoi esporre campi riusabili come costanti:

```java
public final class UserRepository extends HibernateRepository<User, Long> {
    public static final SpecBuilder.FieldSpec<User, String> EMAIL = SpecBuilder.field("u.email");
    public static final SpecBuilder.FieldSpec<User, Boolean> ACTIVE = SpecBuilder.field("u.active");

    public UserRepository(Tx tx) {
        super(tx, User.class);
    }

    public Optional<User> findByEmail(String email) {
        return findOne(EMAIL.eq(email));
    }
}
```

Le query domain-specific possono usare gli helper della base class:

```java
public List<User> findByEmailDomain(String domain) {
    return findMany("from User u where u.email like :email", q ->
        q.setParameter("email", "%@" + domain)
    );
}
```

## Come funziona sotto

- La tx corrente è rappresentata da `HibernateTxStatus`.
- La risorsa esposta al core è una `Session`.
- `Tx.resource(Session.class)` recupera la `Session` dal contesto corrente.
- `REQUIRES_NEW` sospende lo status attivo e apre una nuova `Session`.
- `NOT_SUPPORTED` sospende la tx attiva e continua senza sessione bindata.

## Repository base

`HibernateRepository` fornisce:

- `findById`, `findAll`, `findPage`, `findOne`
- `save`, `update`, `delete`, `saveAll`
- bulk `deleteAll(Spec<T>)` e `updateAll(Spec<T>, T)`
- helper HQL: `hql(...)`, `hqlMutate(...)`

Le classi concrete devono solo implementare query di dominio, non il plumbing transazionale.

## Semantica transazionale

- `REQUIRED`: join o apertura nuova tx.
- `REQUIRES_NEW`: sospensione del contesto corrente.
- `SUPPORTS`: join se c’è tx, altrimenti no-op.
- `NOT_SUPPORTED`: sospende e prosegue senza tx.
- `MANDATORY`: fallisce se non c’è tx.

## Note

- `Session` viene chiusa a fine tx nuova.
- Le synchronizations vengono eseguite al commit/rollback.
- Il backend è pensato per essere usato tramite la base class, non direttamente.
