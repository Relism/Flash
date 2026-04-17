# Package Managers

Package manager selection is explicit via `packageManager`.

- `NPM` -> `package-lock.json`
- `PNPM` -> `pnpm-lock.yaml`
- `YARN` -> `yarn.lock`
- `BUN` -> `bun.lockb`

Install policy:

- `AUTO_IF_LOCK_HASH_CHANGED`: install only when lock hash changes.
- `NEVER`: never auto-install.

Custom commands can override defaults via:

- `installCommand`
- `devCommand`
- `buildCommand`
