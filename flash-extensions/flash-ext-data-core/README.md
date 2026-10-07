# flash-ext-data-core

Transactions and repositories for relational data, independent of the access library. It provides
`Tx` (run work in a transaction), `Data` (repositories and read/write helpers), a small query model
(`Spec`, `Sort`, `Page`), and `@Transactional` for handler classes. The access library is a
`TxManager` and a repository base from a backend module:
[`flash-ext-data-jdbc`](../flash-ext-data-jdbc/README.md) or
[`flash-ext-data-hibernate`](../flash-ext-data-hibernate/README.md).

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-data-core</artifactId>
</dependency>
```

```java
TxManager manager = new HibernateTxManager(sessionFactory);
app.install(new DataModule(manager, HibernateData.create(manager)));

final class ListUsers extends RequestHandler {
    private final Repository<User, Long> users;
    ListUsers(Data data) { this.users = data.repository(User.class); }
    @Override protected Object handle(Request req, Response res) { return users.findAll(); }
}
```

| Page | Content |
|---|---|
| [docs/transactions.md](docs/transactions.md) | `Tx`, propagation, rollback, callbacks, `@Transactional` |
| [docs/repositories.md](docs/repositories.md) | `Data`, `Repository`, `Spec`, `Sort`, `Page` |
| [docs/backends.md](docs/backends.md) | What a backend implements |
