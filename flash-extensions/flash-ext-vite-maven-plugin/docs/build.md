# The build goal

`flash-ext-vite:build` is bound to `prepare-package`, so `mvn test` never needs Node and `mvn package`,
`verify` and `install` always produce a jar with its frontend.

## Steps

1. Installs dependencies with the lockfile pinned (`pnpm install --frozen-lockfile`, `npm ci` and so
   on, or `npm install` without a lockfile).
2. Runs `<package manager> run build` in the project.
3. Replaces `target/classes/flash-vite/` with the project's `dist/`.
4. Writes a `.gz` at maximum compression beside every html, js, mjs, css, json, map, svg, txt, xml,
   webmanifest, ttf, otf and wasm file of 1 KB or more, and keeps it only when it is smaller. Images
   and woff are left alone.

The package manager is chosen as described in [flash-ext-vite](../../flash-ext-vite/docs/module.md#conventions).
Its output appears in the Maven log.

## Parameters

| Parameter | Property | Default | Meaning |
|---|---|---|---|
| `root` | `flash.vite.root` | `${project.basedir}/web` | The Vite project |
| `skip` | `flash.vite.skip` | `false` | Leaves the frontend out, for a backend-only build |

## Failures

The build fails with the reason when there is no `package.json` in `root`, when Node or the package
manager is not on the `PATH`, when the install or build exits non-zero, or when the build leaves no
`dist/index.html`.

## Docker

An image build needs one stage with both a JDK and Node. The frontend needs no stage of its own.
