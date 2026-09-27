# flash-ext-data-core

Shared core for Flash's data layer.

## Purpose

This module defines the transactional contract shared across backend implementations. It does not
talk to Hibernate or JDBC directly: it exposes abstractions and a minimal runtime, nothing else.

## Components

- `TxDefinition`: immutable transaction metadata.
- `TxStatus`: runtime state returned by the manager.
- `TxManager`: the `begin`/`commit`/`rollback` contract.
- `Tx`: runtime orchestration and the per-thread transaction stack.
- `ResourceRegistry`: thread-local storage for resources and synchronizations.
- `Repository<T, ID>`: self-transactional base repository.
- `Spec<T>`: composable predicate.
- `Query<T>`: query object carrying spec, sort and paging.
- `SpecBuilder<T>`: fluent DSL for building typed specs.
- `RepositorySupport<T, ID>`: shared internal helper.
- `TransactionPropagation`: propagation semantics.
- `TransactionIsolation`: isolation level.
- `TxSynchronization`: lifecycle hooks (see below).

## Execution model

The flow is:

1. `Tx.call(definition, work)` calls `TxManager.begin(definition)`.
2. The `TxManager` creates a backend-specific `TxStatus`.
3. The status is pushed onto the thread-local stack.
4. The work uses `Tx.resource(Class)` to obtain the current resource.
5. When the work ends, `Tx` chooses between `commit` and `rollback`.
6. The stack is popped, and the thread-local is cleared once it is empty.

## Supported propagation

- `REQUIRED`: use the active transaction, or open a new one.
- `REQUIRES_NEW`: suspend the current transaction and open a new one.
- `SUPPORTS`: join the active transaction if there is one, otherwise run without a transaction.
- `NOT_SUPPORTED`: suspend the current transaction and run without one.
- `MANDATORY`: require an active transaction.

## Synchronizations (`TxSynchronization`)

Lifecycle hooks for **one** transaction, registered through `Data.afterCommit(...)` (or directly
with `ResourceRegistry.addSynchronization(...)`).

Every callback belongs to exactly the innermost transaction active at registration time, and fires
exactly once, when *that* transaction completes:

- a **joined** inner transaction (`REQUIRED`) is not a transaction of its own, so callbacks
  registered inside one wait for the outermost commit;
- a `REQUIRES_NEW` transaction is, so completing it fires only its own callbacks and leaves the
  suspended outer transaction's pending.

### Which side of the commit each hook sits on

| hook | when | resource |
| --- | --- | --- |
| `beforeCommit(readOnly)` | immediately **before** the real commit | session/connection still **bound**, transaction still active |
| `afterCommit()` / `afterRollback()` | after completion | resource already **unbound** |
| `afterCompletion(outcome)` | after the two above | resource already unbound |

`beforeCommit` is the only hook that can still write through the same resource and have the write
land in the same atomic unit: flush a buffer, stamp an audit row, materialize a derived value. It
is skipped when the transaction is already `rollback-only`, since there is no commit to precede.

Post-completion callbacks run with the resource unbound instead: one that opens its own transaction
gets a **fresh** one rather than joining the transaction that just finished. That is what makes
them the right place to refresh a cache, enqueue a message, or notify anything outside the
database.

### Failure

Throwing from `beforeCommit` **vetoes the commit**: the transaction is rolled back,
`afterRollback`/`afterCompletion(ROLLED_BACK)` fire, and the exception reaches the caller. That is
the reason the hook runs before the commit rather than after — it can still refuse.

The post-completion hooks have no such power: the transaction is already over by the time they run,
so an exception propagates but changes nothing already committed, and stops the callbacks queued
behind it.

## Using `Repository`

`Repository` is the shared base for concrete repositories. Every public operation internally uses a
`REQUIRED` transaction, read-only where applicable.

Subclasses implement the `doXxx(...)` methods:

- `doFind(Query<T>)`
- `doFindOne(Spec<T>)`
- `doFindPage(Query<T>)`
- `doDeleteAll(Spec<T>)`
- `doUpdateAll(Spec<T>, T)`

The old `findAll(...)` and `findPage(...)` overloads were reduced to a combination of `Query<T>` and
`Spec<T>`.

```java
public abstract class Repository<T, ID> {
    protected Repository(Tx tx) { ... }
    protected final <R> R tx(Tx.TxCallable<R> work) { ... }
}
```

## Composing with Flash

`new DataModule(txManager)` (or `new DataModule(txManager, data)`) adds:

- `TxManager`, `Tx` and, when given, `Data` as services, so a repository or handler takes them
  through its constructor
- `@Transactional` on a handler class: the whole request runs in one transaction, `NEVER` refuses
  to run inside one, and `dontRollbackOn` commits and still rethrows
- the manager's `close()` when the app stops, releasing its session factory and connection pool;
  give the manager a pool you want closed with the app

Nothing is global: two apps in one JVM each have their own transactions.

## Implementation notes

- The transaction stack is thread-local and is cleared once it becomes empty.
- Backend resources are suspended and restored for `REQUIRES_NEW` and `NOT_SUPPORTED`.
- `TxSynchronization` is the hook point for commit/rollback/completion callbacks.
- Synchronizations live in a thread-local list; every new transaction records how many were already
  registered when it opened and fires only its own tail, so a `REQUIRES_NEW` does not drag along
  the suspended transaction's callbacks.
