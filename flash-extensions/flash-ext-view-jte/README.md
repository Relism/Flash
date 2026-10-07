# flash-ext-view-jte

[jte](https://jte.gg) templates for Flash, rendered to UTF-8 bytes, plus the classpath's `static/`
directory served from memory. Built on [`flash-ext-view-core`](../flash-ext-view-core/README.md).

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-view-jte</artifactId>
</dependency>
```

```java
App app = App.create().install(new JteModule().global("appName", req -> "Flash")).scan("com.acme.web");

@GET("/")
@Template("pages/home.jte")
final class HomePage extends ViewHandler {
    private final Articles articles;
    HomePage(Articles articles) { this.articles = articles; }

    @Override public ViewModel render(Request req, Response res) {
        return ViewModel.of("page", new Home("Flash and jte", articles.latest()));
    }
}
```

```jte
@import com.acme.web.Home
@param Home page
@param String appName

<h1>${page.title()}</h1>
<small>${appName}</small>
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Templates, globals, settings, compile modes, static files |
