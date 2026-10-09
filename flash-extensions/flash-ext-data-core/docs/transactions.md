# Transactions

## DataModule

```java
app.install(new DataModule(txManager));          // Tx only
app.install(new DataModule(txManager, data));    // Tx and Data
```

Registers `TxManager`, `Tx` and (if given) `Data` as services, and enables `@Transactional` on handler
classes. The manager is closed when the app stops, which releases its connection pool or session
factory.

## Tx

`Tx` runs a function in a transaction of one `TxManager`. Its nesting state is per thread and per
`Tx`, so two `Tx` over two databases never see each other's transactions.

```java
tx.run(() -> { ... });
User user = tx.call(() -> repository.save(new User("ada")));
tx.call(tx.requiresNew(), () -> audit.record(...));
List<Order> recent = tx.read(() -> orders.findAll(page));
```

| Method | Behavior |
|---|---|
| `run(work)`, `call(work)` | Default definition: `REQUIRED`, default isolation, read-write |
| `run(definition, work)`, `call(definition, work)` | With a `TxDefinition` |
| `read(work)` | `SUPPORTS`, read-only: joins the current transaction, or runs without one. Use `call(readOnly(), work)` when several reads must see one snapshot |
| `isActive()` | Whether a transaction is open on this thread |
| `setRollbackOnly()` | Marks the current transaction so it rolls back when the work returns. Throws if none is active |
| `resource(Class)` | The backend's resource: `Connection` for JDBC, `Session` for Hibernate |
| `requiresNew()`, `readOnly()` | Definitions based on the defaults |

Outcomes: the transaction commits when the work returns, and rolls back when it throws or was marked
rollback-only. A `RuntimeException` or `Error` is rethrown unchanged (an `HttpException` keeps its
status). A checked exception from `call` is wrapped in `TxException`.

## TxDefinition

```java
TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW)
                     .withIsolation(TransactionIsolation.SERIALIZABLE)
                     .withReadOnly(true);
```

| Propagation | Behavior |
|---|---|
| `REQUIRED` | Joins the current transaction, or starts one |
| `REQUIRES_NEW` | Suspends the current one and starts a new one |
| `SUPPORTS` | Joins the current one, or runs without a transaction (what that means is the backend's: see its usage page) |
| `MANDATORY` | Joins the current one. Fails with `IllegalStateException` if there is none |
| `NOT_SUPPORTED` | Suspends the current one and runs without a transaction |

Isolation: `DEFAULT` (the database's), `READ_UNCOMMITTED`, `READ_COMMITTED`, `REPEATABLE_READ`,
`SERIALIZABLE`.

A write that joins a read-only transaction fails with `TxException: Cannot write inside a read-only
transaction`. A read joins whatever it finds. Marking an inner joined transaction rollback-only
rolls back the whole transaction.

## Callbacks

`TxSynchronization` hooks into one transaction. Register with `data.afterCommit(runnable)` (needs an
active transaction) or `ResourceRegistry.addSynchronization`.

| Hook | When |
|---|---|
| `beforeCommit(readOnly)` | Inside the transaction, just before commit. It can still write in the same transaction. Throwing rolls the transaction back and propagates. Skipped for a rollback-only transaction |
| `afterCommit()`, `afterRollback()` | After completion, outside the transaction |
| `afterCompletion(outcome)` | After either, with `COMMITTED` or `ROLLED_BACK` |

A callback belongs to the innermost transaction active when it was registered and fires once. A
joined (`REQUIRED`) inner call waits for the outermost commit. A `REQUIRES_NEW` transaction fires
only its own callbacks. A callback that opens its own transaction gets a new one. An exception in
an after-completion hook propagates and stops the callbacks queued behind it, but does not undo the
commit.

## @Transactional

`jakarta.transaction.Transactional` on a handler class runs the request in a transaction.

```java
@POST("/orders")
@Transactional
final class PlaceOrder extends RequestHandler { ... }
```

| `TxType` | Behavior |
|---|---|
| `REQUIRED` (default) | `REQUIRED` |
| `REQUIRES_NEW` | `REQUIRES_NEW` |
| `SUPPORTS` | `SUPPORTS` |
| `MANDATORY` | `MANDATORY` |
| `NOT_SUPPORTED` | `NOT_SUPPORTED` |
| `NEVER` | `NOT_SUPPORTED`, and throws `TxException` if a transaction is already active |

A duplex handler (SSE, gRPC) or a `Response.streaming` body runs after the middleware chain: it runs
in a second transaction with the same definition, which spans the whole stream and holds a connection
for as long. A gRPC call that ends with an error status rolls it back.

`dontRollbackOn` lists exception types that commit the transaction and are then rethrown. Every other
exception, including an `HttpException`, rolls it back. `rollbackOn` is not read. The annotation is
read from the handler class itself.

The transaction covers the handler and the middleware that run after the transactional middleware,
and commits before the response is written. A `Response.streaming` callback runs after the commit.
