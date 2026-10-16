# Clustered directories

Several processes can serve the same directory: instances behind a load balancer, on one host or
several, with the directory on a file system they all mount. The handler says so by its type,
`ClusteredNioDavHandler` instead of `StandaloneNioDavHandler`:

```java
@Dav("/drive/**")
@Authenticated
final class Drive extends ClusteredNioDavHandler {
    @Override protected Path root(Request req) { return Path.of("/mnt/drive", me().id()); }
    @Override protected long quota(Request req) { return me().quotaBytes(); }
}
```

Every process then counts the same bytes in use and honors the same locks, whichever one a request
reaches. With `StandaloneNioDavHandler` they live in each process's memory: a lock taken through one instance is
unknown to the others, so a client's next write through another one is `412`, and each instance
enforces the quota alone.

## .dav

A clustered directory keeps its state in `.dav` under its root:

| Path | Content |
|---|---|
| `.dav/state` | The bytes of finished files, the bytes each process's uploads have taken, the locks |
| `.dav/uploads` | Uploads in progress, moved over their target once complete |

`.dav` is created when a request first reaches the directory. A name starting with a dot hides it on
Linux and macOS; on Windows it is marked hidden. No request reaches it:

- a request that reads or writes `/.dav` or anything below it is `403`, and so is a `Destination` there;
- listings leave it out, and the bytes in use do not count it;
- a symbolic link that leads into it is `403`;
- on a file system that ignores case, every name that leads to it (`.DAV`) is refused as well; on
  Windows, also one with trailing dots or spaces.

A handler that wants clients to see `.dav` overrides `stateVisible()` to return `true`. It is then an
ordinary collection: clients can read it, and change or delete the state, which every process
then reads. The state still lives there.

A `StandaloneNioDavHandler` keeps nothing in `.dav`: there it is a name like any other, served to clients.

A `.dav` deeper in the tree is an ordinary collection. A `.dav` under the root that holds anything
other than this state is left untouched: requests to that directory fail with `500` and an error
that names it.

## How the processes agree

A request holds the directory while it checks and changes something: it locks `.dav/state`, reads it,
and writes it back. Transfers run without it. A `PUT` holds it to take its bytes, then again from
the end of its body until the new content is in place, after checking its conditions and locks once
more. A `COPY` takes its bytes, then copies without it.

## File systems

A clustered directory needs byte-range locks that every process sees, and atomic rename: local file
systems (ext4, XFS, APFS, NTFS), NFS version 4, SMB.

A file system that cannot lock fails the first request to the directory with `500`: `a clustered
directory needs file locks`. One whose locks only hold within each host (NFS mounted with `nolock` or
`local_lock`) cannot be told apart from inside a host: processes on different hosts then do not
agree. Object stores mounted with FUSE and sshfs do not qualify.

## When a process dies

A process that takes bytes or changes files holds a slot: a lock its operating system releases when
the process ends. When an upload does not fit, and the first time a process uses the directory, the
slots of processes that ended are cleared: the bytes they took are given back, their partial uploads
deleted, and the finished files counted again. Until then their bytes stay counted, so a crash never
lets the quota be passed.

## When the state is lost

A `.dav/state` that is deleted, or torn by a power loss, starts over: the finished files are counted
again, each live process writes back what its uploads took, and the locks are gone (clients get
`412` and lock again). Deleting it is how to have the bytes in use counted again after files changed
around the server. Deleting `.dav` while uploads run fails those uploads.

## Limits

| Aspect | Behavior |
|---|---|
| Dead properties | In extended attributes, which every process reads. A file system without them keeps them in each process's memory ([properties.md](properties.md#dead-properties)) |
| Another file system mounted under the root | Uploads to it are copied there from `.dav/uploads` instead of renamed |
| Files written around the server | Not counted until the state is counted again |
