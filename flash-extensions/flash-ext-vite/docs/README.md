# flash-ext-vite

A Vite frontend for a Flash app, in two artifacts:

- **`flash-ext-vite`**, the module. In dev it runs Vite's dev server beside the app. Anywhere
  else it serves the built frontend from the classpath as a single-page app.
- **`flash-ext-vite-maven-plugin`**, the build. It builds the frontend during `mvn package` and puts
  the result where the module reads it, so the jar runs on its own.

## Quick start

A Vite project in `web/` with the template's `dev` and `build` scripts, and:

```java
Server.start(App.create()
        .install(new ViteModule())
        .get("/api/hello", (req, res) -> "hi"), ServerConfig.builder().port(8080).build());
```

```xml
<plugin>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-vite-maven-plugin</artifactId>
    <version>${flash.version}</version>
    <executions>
        <execution>
            <goals><goal>build</goal></goals>
        </execution>
    </executions>
</plugin>
```

That is the whole setup. `FLASH_ENV=dev` gives you Vite with hot reload, and `mvn package` gives you
a jar that serves the frontend.

## Conventions

These are fixed on purpose: each one is the Vite default, or the thing that keeps the two artifacts
in agreement.

| | |
|---|---|
| Project | a Vite project whose `package.json` has a `dev` and a `build` script |
| Build output | `dist/` inside the project (Vite's `build.outDir` default) |
| Inside the jar | `flash-vite/` (`ViteModule.CLASSPATH`) |
| Content-hashed files | everything under `assets/` (Vite's `build.assetsDir` default) |
| Package manager | the one whose lockfile is nearest at or above the project: `pnpm-lock.yaml`, `yarn.lock`, `bun.lock`, `package-lock.json`. With none, npm. A project inside a workspace therefore uses the workspace's lockfile. |
| Node | on the `PATH`, with the package manager. Nothing is downloaded. |

## What you can override

| Module | Default | |
|---|---|---|
| `root(Path)` | `web` | The Vite project, relative to the working directory. Dev only. |
| `devPort(int)` | `5173` | Vite's port in dev. |
| `basePath(String)` | `/` | Where the frontend is served. Vite's `base` must match it. |
| `navigationOnly(boolean)` | `true` | Only browser navigations fall back to `index.html` (see below). `false` gives every `GET` that matches no file the page, as nginx's `try_files` does, which also turns API 404s into the page. Rarely wanted. |

| Plugin parameter | Default | |
|---|---|---|
| `root` / `-Dflash.vite.root` | `${project.basedir}/web` | The Vite project. |
| `skip` / `-Dflash.vite.skip` | `false` | Leaves the frontend out, for a backend-only build. |

There is nothing else to set. If a convention above does not fit, that is a change to this module,
not a configuration option.

## Dev

`ServerConfig.dev` (by default `FLASH_ENV=dev` or `-Dflash.env=dev`) decides the mode, when the
server starts:

1. The dependencies are installed with the lockfile pinned (`pnpm install --frozen-lockfile`,
   `npm ci`, and so on). This is skipped when `node_modules` was already installed from that same
   lockfile: its hash is kept in `node_modules/.flash-vite`, so deleting `node_modules` resets it.
2. The module runs `<pm> run dev --host 127.0.0.1 --port <devPort> --strictPort` and waits up to
   30 s for the port to answer. A port already in use, a script that exits, or a timeout fails the
   boot with the reason. Vite's output is logged at INFO, prefixed `[vite]`.
3. While the app runs, a change to the lockfile (`pnpm add …`) reinstalls and restarts Vite. Vite
   handles changes to its own config itself.
4. When the app stops, Vite and its whole process tree stop with it.

In dev, `basePath/**` answers 404: the browser opens Vite's port, and Vite forwards the
backend's paths to Flash, so `vite.config` needs a `server.proxy` for them:

```ts
server: {
  proxy: { '^/(api|auth)(/|$)': 'http://localhost:8080' },
},
```

## Production

Outside dev, the build is read once at boot from `flash-vite/` on the classpath,
whether that is a directory (`target/classes`) or a jar. A missing build fails the boot and names
the plugin. Compression already happened in the build, so boot only reads files and hashes the few
that revalidate: about 50 ms for a 500-file app. Everything a response carries is prepared at
boot, headers included, so serving allocates nothing: core's `StaticFiles` does the serving.

`GET` and `HEAD` on `basePath/**` answer as follows. Backend routes still win, because Flash
prefers specific routes over the wildcard.

| Request | Answer |
|---|---|
| `basePath` itself (`/`) | `index.html`, whatever the client accepts |
| a built file under `assets/` | `Cache-Control: public, max-age=31536000, immutable`, no `ETag` (it is never asked for again) |
| any other built file (`index.html`, `favicon.svg`, …) | `Cache-Control: no-cache`, revalidated by `ETag` |
| no such file, and the request is a navigation | `index.html`, so the client-side router takes it |
| no such file otherwise (a `fetch` to `/api/typo`, a missing script) | the app's own `404` |
| `If-None-Match` with the current `ETag` | `304` with `ETag` and `Cache-Control`, no body |
| `HEAD` | the `GET` headers, `Content-Length` included, no body |

A navigation is what a browser sends when a person opens a URL: `Sec-Fetch-Mode: navigate`, which
every current browser sets for exactly this purpose, or an `Accept` that names `text/html`, which
covers older browsers, crawlers and `curl -H 'Accept: text/html'`. The check reads the header bytes
in place and allocates nothing. This is stricter than Vite's own dev-server fallback, which also
takes `Accept: */*` and so gives `fetch('/api/typo')` the page. It is the same rule service
workers use for their navigation fallback. With `navigationOnly(false)` the check is skipped.

A `.gz` beside a file is its gzipped form: the plugin writes one for every text file of 1 KB and
more, and it is sent to clients whose `Accept-Encoding` allows gzip (`gzip;q=0` does not), with
`Vary: Accept-Encoding`.

Known limits:

- A browser navigating straight to an API path that does not exist gets the app, which shows its
  own not-found screen. Only an HTML navigation falls back, so API clients always get the 404.
- Every file is held in memory, gzip included. That suits an app's own frontend. Large media belongs
  on a CDN or behind its own route.

## The plugin

The `build` goal is bound to `prepare-package`, so `mvn test` never needs Node, and `mvn package`,
`verify` and `install` always produce a jar with its frontend. It:

1. installs the dependencies with the lockfile pinned;
2. runs `<pm> run build` in the project;
3. replaces `target/classes/flash-vite/` with the project's `dist/`;
4. writes a gzip `.gz` at maximum compression beside every html, js, css, json, map, svg, txt,
   xml, webmanifest, font and wasm file of 1 KB and more, and keeps it only when it is smaller.
   Images and woff are left alone, because they are compressed already.

Missing Node or package manager, a failing install or build, or a build that leaves no
`dist/index.html` fails the Maven build with the reason. The package manager's own output shows in
the Maven log.

A Docker image therefore needs one build stage with both a JDK and Node. The frontend does not need
its own stage.
