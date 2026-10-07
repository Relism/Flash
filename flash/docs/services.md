# Services

A service is an object the app builds from its constructor and hands to whatever needs it. Handlers
are built the same way. There are no annotations and no manual wiring.

```java
public final class UserService {
    private final Database db;
    public UserService(Database db) { this.db = db; }
}

app.add(Database.class, database)      // already built
   .add(UserService.class)             // built by Flash
   .scan("com.acme.api");              // handlers take UserService in their constructor
```

## Registering

| Call | Registers |
|---|---|
| `add(Type.class)` | `Type`, built by its constructor |
| `add(Type.class, Impl.class)` | `Impl`, built by its constructor, under the key `Type` |
| `add(Type.class, instance)` | An instance that already exists. The app owns it from now on |
| `replace(Type.class, instance)` | Removes every binding of `Type` and registers `instance`. For tests |

Registering the same implementation class twice under one type is a no-op, so two modules can both
declare a dependency they share.

## Constructor parameters

Each constructor parameter is resolved by its declared type:

| Parameter | Resolves to |
|---|---|
| `T` | The one binding of `T`. Zero or several fail the boot, naming every candidate and the class that needed it |
| `Optional<T>` | The binding if there is one, empty if none. Several fail the boot |
| `List<T>` | Every binding, in registration order. Empty if none |
| `Services` | The registry itself |

A class is built through its only constructor. With several, exactly one must be public. The order of
`add` calls never matters: Flash builds in dependency order. A cycle fails with the path, for example
`Circular dependency: A -> B -> A`.

## Lifecycle

Services are built when `Server.start` runs, not when they are added. Calling `get`, `find` or `all`
earlier throws. A service is built once and shared by every handler.

Every service that implements `AutoCloseable` is closed when the server stops. Services Flash built
close first, in reverse order of creation. Instances passed already built close last. A failure while
closing is logged and does not stop the others.

## Reading services directly

`app.services()` after start, and the `Services` parameter of `onStart`, expose:

| Method | Returns |
|---|---|
| `get(Type)` | The only binding. Fails on zero or several |
| `find(Type)` | `Optional`. Fails on several |
| `all(Type)` | `List` in registration order |
| `create(Type)` | A new instance of a class that is not a service, built like one. For boot code |

`ServerConfig` is registered as a service during start, so any service or handler can take it as a
constructor parameter.

## Bound

`Bound` is a framework hook: `void bind(Services)` is called once after construction. Flash calls it
on handlers (through `Handler`), on classes built with `create`, and on instances registered already
built. Application code should take its services through the constructor instead. Use `Bound` only
when the object is not built by Flash and needs to look up an optional collaborator.
