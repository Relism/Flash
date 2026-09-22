# flash-ext-vite

A Vite frontend for a Flash app, in two artifacts:

- **`flash-ext-vite`**, the extension. In DEV it runs Vite's dev server beside the app. Anywhere
  else it serves the built frontend from the classpath as a single-page app.
- **`flash-ext-vite-maven-plugin`**, the build. It builds the frontend during `mvn package` and puts
  the result where the extension reads it, so the jar runs on its own.

## Quick start

A Vite project in `web/` with the template's `dev` and `build` scripts, and:

```java
FlashApp.create(8080)
        .install(new ViteExtension())
        .get("/api/hello", (req, res) -> "hi")
        .start();
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
| Inside the jar | `flash-vite/` (`ViteExtension.CLASSPATH`) |
| Content-hashed files | everything under `assets/` (Vite's `build.assetsDir` default) |
| Package manager | the one whose lockfile is nearest at or above the project: `pnpm-lock.yaml`, `yarn.lock`, `bun.lock`, `package-lock.json`. With none, npm. A project inside a workspace therefore uses the workspace's lockfile. |
| Node | on the `PATH`, with the package manager. Nothing is downloaded. |

## What you can override

| Extension | Default | |
|---|---|---|
| `root(Path)` | `web` | The Vite project, relative to the working directory. DEV only. |
| `devPort(int)` | `5173` | Vite's port in DEV. |
| `basePath(String)` | `/` | Where the frontend is served. Vite's `base` must match it. |

| Plugin parameter | Default | |
|---|---|---|
| `root` / `-Dflash.vite.root` | `${project.basedir}/web` | The Vite project. |
| `skip` / `-Dflash.vite.skip` | `false` | Leaves the frontend out, for a backend-only build. |

There is nothing else to set. If a convention above does not fit, that is a change to this module,
not a configuration option.

## DEV

`Flash.DEV` (`FLASH_ENV=dev` or `-Dflash.env=dev`) decides the mode:

1. The dependencies are installed with the lockfile pinned (`pnpm install --frozen-lockfile`,
   `npm ci`, and so on). This is skipped when `node_modules` was already installed from that same
   lockfile: its hash is kept in `node_modules/.flash-vite`, so deleting `node_modules` resets it.
2. The extension runs `<pm> run dev --host 127.0.0.1 --port <devPort> --strictPort` and waits up to
   30 s for the port to answer. A port already in use, a script that exits, or a timeout fails the
   boot with the reason. Vite's output is logged at INFO, prefixed `[vite]`.
3. While the app runs, a change to the lockfile (`pnpm add …`) reinstalls and restarts Vite. Vite
   handles changes to its own config itself.
4. When the app stops (`ctx.onClose`), Vite and its whole process tree stop with it.

The extension registers no routes in DEV. The browser opens Vite's port, and Vite forwards the
backend's paths to Flash, so `vite.config` needs a `server.proxy` for them:

```ts
server: {
  proxy: { '^/(api|auth)(/|$)': 'http://localhost:8080' },
},
```

## Production

Anywhere `Flash.DEV` is false, the build is read once at boot from `flash-vite/` on the classpath,
whether that is a directory (`target/classes`) or a jar. A missing build fails the boot and names
the plugin. Everything a response carries is prepared at boot, headers included, so serving
allocates nothing.

`GET` and `HEAD` on `basePath/**` answer as follows. Backend routes still win, because Flash
prefers specific routes over the wildcard.

| Request | Answer |
|---|---|
| a built file under `assets/` | `Cache-Control: public, max-age=31536000, immutable` |
| any other built file (`index.html`, `favicon.svg`, …) | `Cache-Control: no-cache`, revalidated by `ETag` |
| a path with no file extension (`/content/2`) | `index.html`, so the client-side router takes it |
| a path with an extension that matches no file | `404` |
| `If-None-Match` with the current `ETag` | `304` with `ETag` and `Cache-Control`, no body |
| `HEAD` | the `GET` headers, `Content-Length` included, no body |

Text formats of 1 KB and more (html, js, css, json, svg, fonts, wasm, …) are gzipped once at boot
and sent to clients whose `Accept-Encoding` allows gzip (`gzip;q=0` does not), with
`Vary: Accept-Encoding`. Images and woff are sent as they are, because they are compressed already.

Known limits:

- A client-side route whose last segment has a dot (`/users/ada.lovelace`) is treated as a missing
  file. Keep dots out of client routes, or route them under `basePath` differently.
- Every file is held in memory, gzip included. That suits an app's own frontend. Large media belongs
  on a CDN or behind its own route.

## The plugin

The `build` goal is bound to `prepare-package`, so `mvn test` never needs Node, and `mvn package`,
`verify` and `install` always produce a jar with its frontend. It:

1. installs the dependencies with the lockfile pinned;
2. runs `<pm> run build` in the project;
3. replaces `target/classes/flash-vite/` with the project's `dist/`.

Missing Node or package manager, a failing install or build, or a build that leaves no
`dist/index.html` fails the Maven build with the reason. The package manager's own output shows in
the Maven log.

A Docker image therefore needs one build stage with both a JDK and Node. The frontend does not need
its own stage.
