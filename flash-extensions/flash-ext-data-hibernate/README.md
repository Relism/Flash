# flash-ext-data-hibernate

Hibernate ORM for [`flash-ext-data-core`](../flash-ext-data-core/README.md): a `HibernateTxManager`
over a `SessionFactory`, and ready repositories for any mapped entity.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-data-hibernate</artifactId>
</dependency>
```

```java
TxManager manager = new HibernateTxManager(sessionFactory);
Data data = HibernateData.create(manager);
app.install(new DataModule(manager, data));

Repository<User, Long> users = data.repository(User.class);
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | The transaction manager, repositories, custom HQL |
