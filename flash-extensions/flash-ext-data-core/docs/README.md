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
- `TxSynchronization`: hook lifecycle (vedi sotto).

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

## Synchronization (`TxSynchronization`)

Hook sul ciclo di vita di **una** transazione, registrati con `Data.afterCommit(...)` (o
direttamente con `ResourceRegistry.addSynchronization(...)`).

Ogni callback appartiene esattamente alla transazione più interna attiva al momento della
registrazione, e scatta una volta sola quando *quella* transazione completa:

- una tx interna **joined** (`REQUIRED`) non è una transazione a sé, quindi i callback registrati
  al suo interno aspettano il commit più esterno;
- una tx `REQUIRES_NEW` lo è, quindi completarla fa scattare solo i propri callback e lascia in
  sospeso quelli della transazione esterna sospesa.

### Posizione rispetto al commit

| hook | quando | risorsa |
| --- | --- | --- |
| `beforeCommit(readOnly)` | subito **prima** del commit reale | sessione/connection ancora **bound**, tx ancora attiva |
| `afterCommit()` / `afterRollback()` | dopo il completamento | risorsa già **sganciata** |
| `afterCompletion(outcome)` | dopo i due precedenti | risorsa già sganciata |

`beforeCommit` è l'unico hook che può ancora scrivere sulla stessa risorsa e finire nella stessa
unità atomica: flush di un buffer, riga di audit, valore derivato. Non viene eseguito se la
transazione è già `rollback-only`, perché non c'è nessun commit da precedere.

I callback post-completamento girano invece a risorsa sganciata: uno che apre una propria
transazione ne ottiene una **nuova** invece di agganciarsi a quella appena conclusa. È questo che
li rende il posto giusto per invalidare una cache, accodare un messaggio o notificare qualcosa
fuori dal database.

### Fallimenti

Lanciare da `beforeCommit` **veta il commit**: la transazione va in rollback, scattano
`afterRollback`/`afterCompletion(ROLLED_BACK)` e l'eccezione arriva al chiamante. È il motivo per
cui l'hook gira prima del commit e non dopo — può ancora rifiutare.

Gli hook post-completamento non hanno questo potere: la transazione è già chiusa quando girano,
quindi un'eccezione si propaga ma non cambia nulla di ciò che è stato committato, e blocca i
callback in coda dietro di lei.

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
- Le synchronization sono in una lista thread-local; ogni transazione nuova registra quante ne
  esistevano già alla sua apertura e fa scattare solo la propria coda, così una `REQUIRES_NEW`
  non trascina con sé quelle della transazione sospesa.
