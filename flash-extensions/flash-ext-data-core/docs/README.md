# flash-ext-data-core

Core comune per il layer dati di Flash.

## Scopo

Questo modulo definisce il contratto transazionale condiviso tra le implementazioni backend.
Non parla con Hibernate o JDBC direttamente: espone solo astrazioni e un runtime minimale.

## Componenti

- `TxDefinition`: metadata immutabile della transazione.
- `TxStatus`: stato runtime restituito dal manager.
- `TxManager`: contratto per `begin`, `commit`, `rollback`.
- `Tx`: orchestration runtime e stack transazionale per thread.
- `ResourceRegistry`: storage thread-local di risorse e synchronizations.
- `Repository<T, ID>`: base repository auto-transazionale.
- `Spec<T>`: predicato componibile.
- `Query<T>`: oggetto query con spec, sort e paging.
- `SpecBuilder<T>`: DSL fluente per costruire spec tipizzate.
- `RepositorySupport<T, ID>`: helper interno condiviso.
- `TransactionPropagation`: semantica di propagazione.
- `TransactionIsolation`: livello di isolamento.
- `TxSynchronization`: hook lifecycle.

## Modello di esecuzione

Il flusso è:

1. `Tx.call(definition, work)` chiama `TxManager.begin(definition)`.
2. Il `TxManager` crea un `TxStatus` backend-specific.
3. Lo status viene pushato nello stack thread-local.
4. Il lavoro usa `Tx.resource(Class)` per ottenere la risorsa corrente.
5. A fine lavoro `Tx` decide tra `commit` e `rollback`.
6. Lo stack viene poppato e il thread-local viene pulito se vuoto.

## Propagation supportata

- `REQUIRED`: usa la tx attiva oppure ne apre una nuova.
- `REQUIRES_NEW`: sospende la tx corrente e apre una nuova tx.
- `SUPPORTS`: se esiste una tx attiva si aggancia, altrimenti esegue senza tx.
- `NOT_SUPPORTED`: sospende la tx corrente ed esegue senza tx.
- `MANDATORY`: richiede una tx attiva.

## Uso di `Repository`

`Repository` è la base comune per le repository concrete.
Ogni operazione pubblica usa internamente una tx `REQUIRED` o `REQUIRED` read-only.

Le sottoclassi implementano i metodi `doXxx(...)` del nuovo modello:

- `doFind(Query<T>)`
- `doFindOne(Spec<T>)`
- `doFindPage(Query<T>)`
- `doDeleteAll(Spec<T>)`
- `doUpdateAll(Spec<T>, T)`

I vecchi overload di `findAll(...)` e `findPage(...)` sono stati ridotti a una combinazione di `Query<T>` e `Spec<T>`.

```java
public abstract class Repository<T, ID> {
    protected Repository(Tx tx) { ... }
    protected final <R> R tx(Tx.TxCallable<R> work) { ... }
}
```

## Composizione con Flash

`DataExtension` registra:

- `Tx` nel `FlashContext`
- `TxManager` nel `FlashContext`
- un annotation processor per `@Transactional`

Questo rende il layer dati componibile con il sistema di extension di Flash senza stato globale.

## Note implementative

- Lo stack transazionale è thread-local e viene ripulito quando torna vuoto.
- Le risorse backend sono sospese e ripristinate per `REQUIRES_NEW` e `NOT_SUPPORTED`.
- `TxSynchronization` è il punto di aggancio per hook di commit/rollback/completion.
