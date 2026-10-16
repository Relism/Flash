# Quota

A handler limits the bytes its storage may hold by overriding `quota`:

```java
@Override protected long quota(Request req) {
    return quotas.forRole(SecurityIdentity.current().user(Account.class).role());
}
```

The default, `Long.MAX_VALUE`, is no limit. A negative value counts as `0`. The quota belongs to the
storage the request sees: with a directory per user, each user has their own.

## Bytes in use

The bytes in use are the bytes of finished files plus the bytes uploads in progress have taken.

The finished files are counted with the storage's `usage` the first time a request needs them: a
write under a quota, or a `PROPFIND` that asks for the quota. Every `PUT`, `COPY` and `DELETE` then
keeps the count up to date. Files a handler hides count; dead properties and collections do not.

Files changed around the server are not seen until the files are counted again: when the app
restarts, or, for a [clustered directory](clustered.md#when-the-state-is-lost), when `.dav/state` is deleted.

## Taken before writing

A `PUT` under a quota takes its bytes before the storage writes them:

- with a length (`Content-Length`, or Finder's `X-Expected-Entity-Length`), all of it at once,
  before the body is read;
- without one (chunked), 1 MiB at a time as the body arrives; when a block does not fit, only the
  bytes that arrived.

What an upload took and did not write is given back when it ends, whatever the outcome. Uploads to
one storage at the same time never pass the quota together, through one process or, for a
[clustered directory](clustered.md), through several.

A replaced file keeps its bytes until the new content is in place, so a replacement needs room for
both.

## Reported

`PROPFIND` reports the two properties of RFC 4331 when they are asked for by name, on any resource of
the storage:

| Property | Value |
|---|---|
| `quota-used-bytes` | The bytes in use |
| `quota-available-bytes` | What is left of the quota, or the storage's free space when that is smaller. Without a quota, the free space |

Without a quota and without a free space the storage knows, both are listed under `404`. Finder asks
for them ([clients.md](clients.md)).

## Enforced

| Request | Under a quota |
|---|---|
| `PUT` with a length | `507` before the body is read, when it does not fit. Over HTTP/1.1 a body over [`maxDiscardedBodySize`](../../../flash/docs/config.md#limits) is then not read: the connection closes ([http1.md](../../../flash/docs/http1.md#unread-bodies)) |
| `PUT` without a length (chunked) | `507` as soon as the body passes what is left. a directory handler keeps the previous content |
| `COPY` | `507` before anything is copied, when the copy does not fit. A replaced destination's bytes count as free: it is deleted first |
| `MOVE`, `MKCOL`, `PROPPATCH`, `LOCK` | Not limited |
| `DELETE` | Frees the bytes of what it removed |

The `507` body is `<D:error xmlns:D="DAV:"><D:quota-not-exceeded/></D:error>`. While the bytes in use
are over a quota that was lowered, every `PUT` and `COPY` is refused until enough is deleted.

A store that is full answers `507` with `<D:sufficient-disk-space/>`, and one whose own quota is
reached `507` with `<D:quota-not-exceeded/>`: see [engines.md](engines.md#errors).
