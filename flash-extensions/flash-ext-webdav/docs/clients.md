# Clients

What each client needs from the server, and what it cannot be given. Apple and Microsoft do not
publish current documentation of their WebDAV clients; the facts below cite the sources they come
from.

## Tested automatically

With `mvn test -Pinterop`, each client runs in a container started by Testcontainers (Docker
required) against a `StandaloneNioDavHandler` mount behind HTTP Basic:

| Client | What runs |
|---|---|
| [litmus](https://github.com/notroj/litmus) | Every suite: `basic`, `copymove`, `props`, `locks`, `http`, with no warning |
| [rclone](https://rclone.org/webdav/) | `copy` of a tree with a non-ASCII name, `check`, `moveto`, `copyto`, `purge` |
| [cadaver](https://notroj.github.io/cadaver/) | `mkcol`, `put`, `ls`, `copy`, `move`, `lock`, `propset`, `propget`, `unlock`, `get`, `rm`, `rmcol` |

rclone uses `vendor=other`.

## macOS Finder

Source: [sabre.io, Finder](https://sabre.io/dav/clients/finder/).

| Finder | Here |
|---|---|
| Mounts a server without `LOCK` read-only | A request that may write is served `LOCK`; a read-only one is not, on purpose |
| Sends `PUT` bodies chunked | Accepted; `X-Expected-Entity-Length` is passed to the storage as the length |
| Needs `Content-Length` on every file | Always sent on `GET` and `HEAD` |
| Reads `getlastmodified` as UTC | Always GMT |
| Sends names in decomposed Unicode (NFD) | Stored as sent: a name made on a Mac and the same name typed elsewhere may be two files |
| Asks for `quota-available-bytes` and `quota-used-bytes` | Answered with the handler's quota and the storage's free space ([quota.md](quota.md#reported)) |
| Writes `._name` AppleDouble files and `.DS_Store` | Ordinary files |
| Sends many `PROPFIND` requests | Mind rate limits ([security.md](security.md#limits)) |
| Supports Basic | With [flash-ext-security-basic](../../flash-ext-security-basic/README.md), over TLS |

Apple described 10-minute locks refreshed every 5 minutes
([w3c-dist-auth](https://lists.w3.org/Archives/Public/w3c-dist-auth/2002JanMar/0334.html));
`maxLockTimeout` cannot be set below 600 seconds for that reason.

## Windows Explorer (WebClient)

Sources: [sabre.io, Windows](https://sabre.io/dav/clients/windows/),
[WebDAV redirector registry settings](https://blogs.iis.net/robert_mcmurray/webdav-redirector-registry-settings).

| Windows | Here |
|---|---|
| Sends `OPTIONS /` before using a share at any path, and gives up on `404` or `401` | `DavModule` answers it without authentication ([security.md](security.md#options-above-a-mount)) |
| Needs `MS-Author-Via: DAV` | Sent with every `OPTIONS` answer |
| Sends `UNLOCK` with a bare lock token | Accepted |
| Sets `Win32CreationTime`, `Win32LastAccessTime`, `Win32LastModifiedTime`, `Win32FileAttributes` with `PROPPATCH`; copying files fails without it ([Tomcat bug 69046](https://bz.apache.org/bugzilla/show_bug.cgi?id=69046)) | Kept as dead properties |
| May show `displayname` instead of the name | Never produced |
| Does not ask for `quota-available-bytes` and `quota-used-bytes`; the Properties of a mapped drive show sizes the server never sent | The quota is enforced all the same |
| Copies a file into its local cache first, then sends it: an empty `PUT`, `LOCK`, `PROPPATCH`, then the whole file in one `PUT` when it is closed. The copy dialog waits at 99% during that last `PUT` | A quota refusal can only come at that `PUT` |
| Reads the answer to a `PUT` only after sending the whole file; a connection closed before that is "network error", and the copy is tried once more | A refused upload over [`maxDiscardedBodySize`](../../../flash/docs/config.md#limits) closes the connection: raise it above the largest file for Explorer to show the refusal |
| Shows a `507` as error `0x80070718` ("not enough quota") | `507` with `quota-not-exceeded`, when the answer is read (row above) |

What only the client can change, under `HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters`:

| Setting | Default | Effect |
|---|---|---|
| `BasicAuthLevel` | `1` | Basic over HTTPS only; `2` allows plain HTTP, which is not advisable |
| `FileSizeLimitInBytes` | 50,000,000 | Larger files cannot be transferred: an upload succeeds, but opening the file fails |

The WebClient service must be running. Older versions accept only the default port.

## Not verified here

Finder and Explorer cannot run in this project's automated tests. Finder's rows come from the cited
sources and from the behavior of this module. Explorer's last four rows, and the effect of
`FileSizeLimitInBytes`, were seen in a manual session against a `StandaloneNioDavHandler` mount with a quota.
