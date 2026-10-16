# flash-ext-data-jdbc

Plain JDBC for [`flash-ext-data-core`](../flash-ext-data-core/README.md): a `JdbcTxManager` over a
`DataSource` and a `JdbcRepository` base class where you write the SQL.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-data-jdbc</artifactId>
</dependency>
```

```java
TxManager manager = new JdbcTxManager(dataSource);
app.install(new DataModule(manager));

final class Users extends JdbcRepository<User, Long> {
    Users(Tx tx) { super(tx, "users", "id"); }
    @Override protected User mapRow(ResultSet rs) throws SQLException { return new User(rs.getLong("id"), rs.getString("name")); }
    @Override protected String insertSql() { return "insert into users(name) values (?)"; }
    @Override protected void bindInsert(PreparedStatement ps, User u) throws SQLException { ps.setString(1, u.name()); }
    @Override protected String updateSql() { return "update users set name = ? where id = ?"; }
    @Override protected void bindUpdate(PreparedStatement ps, User u) throws SQLException { ps.setString(1, u.name()); ps.setLong(2, u.id()); }
}
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | The transaction manager, the repository base, SQL helpers |
