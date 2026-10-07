# flash-ext-vite

A [Vite](https://vite.dev) frontend for a Flash app. In development it runs Vite's dev server as a
child process of the app. Otherwise it serves the build that
[`flash-ext-vite-maven-plugin`](../flash-ext-vite-maven-plugin/README.md) packaged into the jar, as a
single-page app.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-vite</artifactId>
</dependency>
```

```java
Server.start(App.create()
        .install(new ViteModule())
        .get("/api/hello", (req, res) -> "hi"), config);
```

| Page | Content |
|---|---|
| [docs/module.md](docs/module.md) | `ViteModule` options and conventions |
| [docs/development.md](docs/development.md) | The dev server |
| [docs/production.md](docs/production.md) | Serving the build |
