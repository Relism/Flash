# flash-ext-data-hibernate

Hibernate backend for `flash-ext-data-core`.

## Purpose

This module implements `TxManager` on top of a `SessionFactory` and provides a Hibernate-centric
repository base class.

## How to use it

### 1. Create the manager

```java
SessionFactory sessionFactory = ...;
HibernateTxManager txManager = new HibernateTxManager(sessionFactory);
DataExtension extension = new DataExtension(txManager);
```

### 2. Install the extension in Flash

The extension registers `Tx` and `TxManager` in the `FlashContext`. Class-based handlers annotated
with `@Transactional` are wrapped automatically.

### 3. Define a repository

```java
public final class UserRepository extends HibernateRepository<User, Long> {
    public UserRepository(Tx tx) {
        super(tx, User.class);
    }
}
```

With the query/spec model you can expose reusable fields as constants:

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

Domain-specific queries can use the base class helpers:

```java
public List<User> findByEmailDomain(String domain) {
    return findMany("from User u where u.email like :email", q ->
        q.setParameter("email", "%@" + domain)
    );
}
```

## How it works underneath

- The current transaction is represented by `HibernateTxStatus`.
- The resource exposed to the core is a `Session`.
- `Tx.resource(Session.class)` retrieves the `Session` from the current context.
- `REQUIRES_NEW` suspends the active status and opens a new `Session`.
- `NOT_SUPPORTED` suspends the active transaction and continues with no session bound.

## Repository base class

`HibernateRepository` provides:

- `findById`, `findAll`, `findPage`, `findOne`
- `save`, `update`, `delete`, `saveAll`
- bulk `deleteAll(Spec<T>)` and `updateAll(Spec<T>, T)`
- HQL helpers: `hql(...)`, `hqlMutate(...)`

Concrete classes only have to implement domain queries, never the transactional plumbing.

## Transactional semantics

- `REQUIRED`: join, or open a new transaction.
- `REQUIRES_NEW`: suspend the current context.
- `SUPPORTS`: join if a transaction exists, otherwise no-op.
- `NOT_SUPPORTED`: suspend and continue without a transaction.
- `MANDATORY`: fail if there is no transaction.

## Notes

- The `Session` is closed when a new transaction ends.
- Synchronizations registered in a transaction fire when *that* transaction completes:
  `beforeCommit` while it is still active and the `Session` still bound, the post-completion hooks
  once it is unbound. See `flash-ext-data-core/docs/README.md` for the full contract.
- This backend is meant to be used through the base class, not directly.
