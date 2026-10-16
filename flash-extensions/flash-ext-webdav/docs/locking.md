# Locking

Write locks of RFC 4918, exclusive and shared, with depth `0` or `infinity`. Locks are held in memory
by `DavModule` and are lost when the app stops; a [clustered directory](clustered.md) keeps them in
`.dav/state`, for every process that serves it. They belong to the storage a request sees, not to its
URL: two mounts over the same directory share them ([handlers.md](handlers.md#locks-and-spaces)).
A read-only request is not served `LOCK` and `UNLOCK`.

## LOCK

```
LOCK /files/report.odt
Timeout: Second-600
Depth: 0

<D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope>
  <D:locktype><D:write/></D:locktype><D:owner>alice</D:owner></D:lockinfo>
```

| Case | Answer |
|---|---|
| New lock on an existing resource | `200`, `Lock-Token: <urn:uuid:...>` and the `lockdiscovery` |
| New lock on an unmapped URL | `201`: an empty file is created, as RFC 4918 requires |
| A lock already held conflicts | `423` with `<D:no-conflicting-lock/>` |
| No body: a refresh | `200` when the `If` header names a lock covering the resource, its timeout renewed; `412` otherwise |
| Not a `lockinfo` of one scope and the `write` type, or `Depth: 1` | `400` |
| The resource already holds 32 locks, or the storage 1,024 | `503` |

A lock conflicts with any lock on the resource, on an ancestor with depth `infinity`, or, for a deep
lock, below it, unless both are shared. `owner` is kept as sent and returned in `lockdiscovery`.

`Timeout` is a list; the first `Second-n` or `Infinite` is granted, capped at `maxLockTimeout`
(1 hour unless set). Without one, the maximum is granted.

## UNLOCK

`Lock-Token` names the lock, with or without `<` `>` (Windows sends it bare). `204` when the lock
covers the request URI; `409` with `<D:lock-token-matches-request-uri/>` otherwise; `400` without
the header.

## Writing under a lock

A request that changes a resource must name the token of a lock that applies in its `If` header:

| Request | Locks that apply |
|---|---|
| `PUT`, `PROPPATCH` | Those covering the resource; for a new resource also its parent's |
| `MKCOL`, `LOCK` on an unmapped URL | Those covering the path and its parent's |
| `DELETE`, `MOVE` (source) | Those covering the resource, its parent's, and every lock below it |
| `COPY`, `MOVE` (destination) | Those covering the destination and its parent's, and below it when it exists |

Without one of their tokens the answer is `423` with `<D:lock-token-submitted>` naming the locked
resource. One token of any lock that applies is enough.

## The If header

`If` is evaluated for every method but `UNLOCK`, before the lock check: when none of its lists
holds, the answer is `412`.

- An untagged list is about the request URI; a tagged list (`<url> (...)`) about that resource, which
  must be in the same mount and on the same host.
- A list holds when all its conditions do. A state token holds when it is a lock covering the
  resource; an entity tag (`["..."]`) when it equals the resource's, a weak one compared by value.
  `Not` inverts a condition. `<DAV:no-lock>` is never a lock.
- The tokens named without `Not` are the ones the request submits.

A header that does not follow the grammar of RFC 4918 §10.4 is `400`.
