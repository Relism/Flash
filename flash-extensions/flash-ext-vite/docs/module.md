# ViteModule

```java
new ViteModule().root(Path.of("web")).devPort(5173).basePath("/").navigationOnly(true)
```

| Option | Default | Meaning |
|---|---|---|
| `root(Path)` | `web` | The Vite project, relative to the working directory. Used in development only |
| `devPort(int)` | `5173` | Vite's port in development |
| `basePath(String)` | `/` | Where the frontend is served. Vite's `base` must match |
| `navigationOnly(boolean)` | `true` | Whether only browser navigations fall back to `index.html`. See [production.md](production.md#fallback) |

The module registers one `GET` route at `basePath/**` (or `/**` for the root). More specific routes
win over it.

## Conventions

These are fixed. Each is a Vite default, or what keeps the module and the plugin in agreement.

| | |
|---|---|
| Project | A Vite project whose `package.json` has a `dev` and a `build` script |
| Build output | `dist/` in the project |
| Location in the jar | `flash-vite/` (`ViteModule.CLASSPATH`) |
| Content-hashed files | Everything under `assets/` |
| Package manager | The one whose lockfile is nearest at or above the project: `pnpm-lock.yaml`, `yarn.lock`, `bun.lock`, `package-lock.json`. With none, npm. A project in a workspace uses the workspace's lockfile |
| Node | On the `PATH`. Nothing is downloaded |

## Mode

The mode follows [`ServerConfig.dev`](../../../flash/docs/config.md#development-mode), decided when the
server starts.
