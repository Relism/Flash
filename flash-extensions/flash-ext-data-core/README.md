# flash-ext-data-core

Transactions and repositories for relational data, independent of the access library. It provides
`Tx` (run work in a transaction), `Data` (repositories and read/write helpers), a small query model
(`Spec`, `Sort`, `Query`), keyset paging (`PageRequest`, `Page`), and `@Transactional` for handler
classes. The access library is a
`TxManager` and a repository base from a backend module:
[`flash-ext-data-jdbc`](../flash-ext-data-jdbc/README.md) or
[`flash-ext-data-hibernate`](../flash-ext-data-hibernate/README.md).

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-data-core</artifactId>
</dependency>
```

```java
TxManager manager = new HibernateTxManager(sessionFactory);
app.install(new DataModule(manager, HibernateData.create(manager)));

record User(long id, @Sortable String name) {}

@GET("/users")
final class ListUsers extends JsonHandler<PageRequest<User>, Page<User>> {
    private final Repository<User, Long> users;
    ListUsers(Data data) { this.users = data.repository(User.class); }
    @Override protected Page<User> handle(Request req, Response res, PageRequest<User> page) {
        return users.findPage(page);
    }
}
```

| Page | Content |
|---|---|
| [docs/transactions.md](docs/transactions.md) | `Tx`, propagation, rollback, callbacks, `@Transactional` |
| [docs/repositories.md](docs/repositories.md) | `Data`, `Repository`, `Spec`, `Sort`, `Query` |
| [docs/paging.md](docs/paging.md) | `Pager`, `PageRequest`, `Page`, `@Sortable`, and paging through serde and OpenAPI |
| [docs/backends.md](docs/backends.md) | What a backend implements |
