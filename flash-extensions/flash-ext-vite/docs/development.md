# Development

With `dev` on, starting the app starts Vite.

1. **Install.** Dependencies are installed with the lockfile pinned: `pnpm install --frozen-lockfile`,
   `yarn install --frozen-lockfile`, `bun install --frozen-lockfile` or `npm ci`. Without a lockfile,
   `npm install`. The install is skipped when `node_modules` was installed from this lockfile: a hash
   of the lockfile is kept in `node_modules/.flash-vite`, so deleting `node_modules` resets it.
2. **Run.** The module runs `<pm> run dev --host 127.0.0.1 --port <devPort> --strictPort` in the
   project and waits up to 30 seconds for the port to answer. A port already in use, a script that
   exits, or the timeout fails the boot with the reason. Vite's output is logged at INFO, prefixed
   `[vite]`.
3. **Watch.** Every 2 seconds the lockfile's modification time is checked. When it changes, the
   dependencies are reinstalled and Vite is restarted. Vite handles changes to its own config itself.
4. **Stop.** When the app stops, Vite and its whole process tree are stopped: gracefully first,
   forcibly after 5 seconds.

A missing `package.json` fails the boot: `No package.json in <path>`.

## Proxying the backend

In development the browser opens Vite's port, and `GET basePath/**` on Flash answers `404`. Vite has to
forward backend paths to Flash. Add a proxy to `vite.config`:

```ts
export default defineConfig({
  server: {
    proxy: { '^/(api|auth)(/|$)': 'http://localhost:8080' },
  },
})
```
