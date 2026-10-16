# Properties

## PROPFIND

| `Depth` | Answer |
|---|---|
| `0` | The resource |
| `1` | The resource and its members |
| missing or `infinity` | `403` with `<D:error><D:propfind-finite-depth/></D:error>`, as RFC 4918 allows |
| anything else | `400` |

The body chooses what is reported. An empty body is `allprop`. A body that is not well-formed XML, or
whose root is not `DAV:propfind` naming `allprop`, `propname` or `prop`, is `400`.

| Body | Reported |
|---|---|
| `<allprop/>` | The live properties below, except the quota ones, and every dead property. `<include>` adds names |
| `<propname/>` | The names of the same properties, empty |
| `<prop>...</prop>` | The named ones; those that do not exist or do not apply are listed under `404` |

The answer is `207` with `application/xml; charset=utf-8`, written while it is produced. Each member
is one `<D:response>`; collections have a trailing `/` in their `<D:href>`.

A member whose dead properties the storage cannot read, such as a directory the server's user may
list but not enter, is still listed with its live properties. Dead properties asked for by name are
then reported under the storage's status: `403` when access is denied.

### Live properties

| Property | Value |
|---|---|
| `creationdate` | ISO 8601, UTC, in seconds |
| `getcontentlength` | Bytes; files only |
| `getcontenttype` | Files only |
| `getetag` | The storage's entity tag |
| `getlastmodified` | `EEE, dd MMM yyyy HH:mm:ss GMT` |
| `resourcetype` | `<D:collection/>` for a collection, empty for a file |
| `supportedlock` | Exclusive and shared write locks; empty for a read-only request |
| `lockdiscovery` | Every lock that covers the resource |
| `quota-available-bytes`, `quota-used-bytes` | RFC 4331; only when asked for by name. See [quota.md](quota.md#reported) |

`displayname` is never produced: some clients show it instead of the name in the URL.

## PROPPATCH

The body is a `DAV:propertyupdate` of `set` and `remove`, applied in order. The update is atomic: when
it names a live property, nothing is changed, the live ones are `403` and the others `424 Failed
Dependency`. Otherwise every property is `200`, or all of them carry the status the storage failed
with. Removing a property that does not exist is not an error. The answer is `207`.

## Dead properties

A dead property is any property a client sets: Windows' `Win32CreationTime`,
`Win32LastAccessTime`, `Win32LastModifiedTime` and `Win32FileAttributes` (namespace
`urn:schemas-microsoft-com:`), a client's own. Each is kept as its element's content, written back as
XML with every namespace declared where it is used, so it returns as it was set.

They are read and written by name (`javax.xml.namespace.QName`) through the handler's
`properties(Request, DavPath)` and `properties(Request, DavPath, Map)`, which a handler can
override ([handlers.md](handlers.md#each-operation)). Where they live:

| Storage | Dead properties |
|---|---|
| One that implements `DavStorage.Properties` ([engines.md](engines.md#properties)) | Wherever it keeps them |
| `StandaloneNioDavHandler` or `ClusteredNioDavHandler` on a file store with extended attributes | One user attribute per property, named `dav:{namespace}name`, in UTF-8 |
| Anything else | In memory, by storage, at most 65,536 characters (names and values) per resource, `507` beyond. Lost when the app stops, and not seen by the other processes of a [clustered directory](clustered.md). Logged at `INFO` the first time a handler keeps one |

Dead properties are copied and moved with their resources, and dropped when they are deleted.
