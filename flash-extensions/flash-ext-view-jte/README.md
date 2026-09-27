# flash-ext-view-jte

[jte](https://jte.gg) templates for Flash, rendered straight to UTF-8 bytes.

```java
App.create()
    .install(new JteModule().global("appName", req -> "Flash"))
    .scan("com.example.web");
```

```java
@GET("/")
@Template("pages/home.jte")
public final class HomePage extends ViewHandler {
    private final Articles articles;

    public HomePage(Articles articles) { this.articles = articles; }

    @Override public ViewModel render(Request req, Response res) {
        return ViewModel.of("page", new Home("Flash + jte", articles.latest()));
    }
}
```

```jte
@import com.example.web.Home
@param Home page
@param String appName

<h1>${page.title()}</h1>
<small>${appName}</small>
```

`@Template` is inherited, so a base class can name the layout its subclasses fill.

## Globals

Globals are merged flat into the model, so a template declares one `@param` for each global it
uses, named like the global; a template that declares none never sees them. A model key that
collides with a global fails the render. Every global is computed on every render, so keep them
cheap.

## Settings

| Method | Default | |
|---|---|---|
| `templateRoot(String)` | `templates` | read in dev: a directory at that path if there is one, the classpath otherwise |
| `contentType(gg.jte.ContentType)` | `Html` | `Html` escapes output and answers `text/html`, `Plain` answers `text/plain` |
| `precompiled(boolean)` | outside dev | templates compiled at build time by jte's Maven plugin; in dev they compile on the fly |
| `classes(Path)` | `jte-classes` | where compiled templates are written in dev and read otherwise |
| `statics(String)` | `/static` | where the classpath's `static/` directory is served; `null` serves none |

Dev is `ServerConfig.dev`.

## Static files

`static/` on the classpath is read once at boot and served from memory by core's
`StaticFiles`: a `.gz` beside a file is sent to clients that accept gzip, a name with a hex
content hash (`app.3f2a91bc.js`) is cached for a year, anything else revalidates by ETag. There
are no byte ranges: serve large media from a CDN.
