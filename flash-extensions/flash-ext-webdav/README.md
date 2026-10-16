# flash-ext-webdav

WebDAV (RFC 4918, classes 1 and 2): files, collections, properties, locks and quotas, over a
directory or an engine of your own. Finder, Windows Explorer, rclone and cadaver can mount or sync it.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-webdav</artifactId>
</dependency>
```

```java
import dev.relism.flash.ext.webdav.nio.StandaloneNioDavHandler;   // the engine over java.nio.file

App.create()
    .install(new SecurityModule(), new BasicAuth(accounts::byUsername), new DavModule())
    .scan("com.acme");

@Dav("/drive/**")
@Authenticated
final class Drive extends StandaloneNioDavHandler {
    @Override protected Path root(Request req) {            // a directory per user, created when missing
        return Path.of("/srv/drive", me().id());
    }

    @Override protected long quota(Request req) {           // reported to clients, enforced with 507
        return me().quotaBytes();
    }

    private static Account me() {
        return SecurityIdentity.current().user(Account.class);
    }
}
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Mounts, `DavModule`, the methods and their answers |
| [docs/handlers.md](docs/handlers.md) | `StandaloneNioDavHandler` and `ClusteredNioDavHandler`, what a caller may do, control over each operation |
| [docs/quota.md](docs/quota.md) | Quotas: counted, reported, enforced |
| [docs/clustered.md](docs/clustered.md) | One directory served by several processes: `.dav`, file systems, crashes |
| [docs/engines.md](docs/engines.md) | Writing an engine: `DavStorage`, `DavPath`, `DavNode` |
| [docs/properties.md](docs/properties.md) | `PROPFIND`, `PROPPATCH`, live and dead properties |
| [docs/locking.md](docs/locking.md) | `LOCK`, `UNLOCK`, the `If` header |
| [docs/security.md](docs/security.md) | Authentication, paths, links, XML, limits |
| [docs/clients.md](docs/clients.md) | Finder, Windows, rclone, cadaver: what each needs |
