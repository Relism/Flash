# Repositories and queries

## Data

`Data` is a gateway around a `Tx` and a `RepositoryFactory`.

```java
Repository<User, Long> users = data.repository(User.class);
data.write(() -> { users.save(a); users.save(b); });
List<User> all = data.read(() -> users.findAll());
```

| Method | Behavior |
|---|---|
| `repository(Class)` | One repository per entity type, created once and shared. It keeps no transaction state |
| `read(work)` | `tx.read(work)`: joins the current transaction, or runs without one. Variants for a result: `read(TxCallable)` |
| `write(work)` | Runs in a read-write transaction |
| `afterCommit(runnable)` | Runs after the enclosing transaction commits |
| `tx()` | The `Tx` |

## Repository

Every method joins the current transaction. Without one, a read runs as `SUPPORTS` (no transaction of
its own on JDBC) and a write opens a read-write transaction. Wrap several calls in `data.write` or
`tx.run` to make them one transaction.

| Method | Returns |
|---|---|
| `findById(id)`, `findOne(spec)` | `Optional<T>` |
| `existsById(id)`, `count()`, `count(spec)` | `boolean`, `long` |
| `findAll()`, `findAll(spec)`, `findAll(sort)`, `findAll(query)` | `List<T>` |
| `findPage(request)`, `findPage(request, scope)` | `Page<T>`: the page a `PageRequest` asks for. See [paging.md](paging.md) |
| `save(entity)`, `saveAll(entities)` | The saved entity or entities |
| `update(entity)` | The updated entity |
| `delete(entity)`, `deleteById(id)`, `deleteAll(entities)` | `void` |
| `deleteAll(spec)` | Number of rows deleted |
| `updateAll(spec, patch)` | Number of rows. Not provided by the bundled backends |


## Spec

A `Spec<T>` is a condition that renders a fragment and binds values as parameters.

```java
Spec<User> adults = SpecBuilder.<User, Integer>field("age").ge(18);
List<User> result = users.findAll(adults.and(SpecBuilder.field("status").eq("ACTIVE")));
```

`SpecBuilder.field(column)` offers `eq`, `neq`, `like`, `isNull`, `isNotNull`, `in`, `gt`, `ge`, `lt`,
`le` and `between`. `Spec` combines with `and`, `or` and `not`; an argument of `and` and `or` needs no
type witness. `Spec.all()` matches everything and `Spec.none()` nothing.

**Column names and sort columns are written into the statement as given.** Only values are bound as
parameters. Never pass user input as a column name.

## Query, Sort

```java
Query<User> query = Query.<User>all().where(active).orderBy(Sort.by("name").thenDesc("createdAt")).limit(20);
List<User> rows = users.findAll(query);
```

`Sort.by(column)`, `Sort.desc(column)`, `Sort.by(column, asc)` and `then`/`thenDesc` build an ordering.
`limit(n)` reads at most `n` rows; `0`, the default, reads them all. There is no offset: for pages a
client walks, use [paging](paging.md), whose cost does not grow with the position.
