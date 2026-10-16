# Flash: Agent Guidelines

This document defines the conventions and rules that all agents (AI or human) must follow
when working on this repository. Read it entirely before making any change.

---

## Repositories

| Repository | What |
|------------|------|
| [Relism/Flash](https://github.com/Relism/Flash) | This repository |
| [Relism/FastPathRouter](https://github.com/Relism/FastPathRouter) | `fpr-core`, the router `flash` depends on, released the same way |
| [Relism/FlashDocs](https://github.com/Relism/FlashDocs) | The documentation site |

Everything lives on GitHub and is published through JitPack. Nothing is built, published or
resolved from Gitea.

---

## Git Workflow

### Day-to-day flow

1. Branch from an up-to-date `master`, named as in the table below.
2. Commit with Conventional Commits. Commits on a branch need not be signed: the squash merge
   commit on `master` is created and signed by GitHub.
3. Push and open a PR whose title is the Conventional Commit that should land on `master`.
4. Wait for the required checks, then squash merge. Auto-merge can be armed while checks run.
   The branch is deleted on merge.
5. The change waits in the release PR that release-please keeps up to date, until a maintainer
   releases it. See [Release Process](#release-process-for-maintainers-only).

Dependabot PRs follow the same path: review the changelog of the update, merge once green.

### Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| New feature | `feature/<scope>/<short-description>` | `feature/ext-oidc/pkce-support` |
| Bug fix | `fix/<scope>/<short-description>` | `fix/core/router-npe` |
| CI/infra changes | `feature/ci/<short-description>` | `feature/ci/dependabot-groups` |

Rules:
- Always branch from `master`.
- Branch names are lowercase, words separated by `-`.
- Never push directly to `master`.
- Never create `develop`, `release/*`, or any other long-lived branch. A fix for a released
  version lands on `master` and ships in the next PATCH release.

### Commit Messages: Conventional Commits

Format: `<type>(<scope>): <short description>`

| Type | When to use |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code change without feature/fix |
| `test` | Adding or updating tests |
| `docs` | Documentation only |
| `chore` | Build, deps, tooling, no production code |
| `ci` | Changes to GitHub Actions workflows |

Allowed scopes: `core`, `testing`, `ext-jackson`, `ext-avaje-jsonb`, `ext-openapi`, `ext-security`,
`ext-oidc`, `ext-protobuf-java`, `ext-grpc`, `ext-ndjson`, `ext-webdav`, `ext-validation-protovalidate`, `ext-micrometer`,
`ext-view-core`, `ext-view-jte`, `ext-view-thymeleaf`, `ext-limiter`, `ext-vite`,
`ext-mcp`, `ext-validation`, `ext-scheduler`, `ext-data-core`, `ext-data-jdbc`, `ext-data-hibernate`,
`ext-cache-core`, `ext-cache-caffeine`, `release`, `deps`, `ci`.

Examples:
```
feat(ext-oidc): add PKCE support
fix(core): fix NPE in RouteHandler when path is null
chore(deps): upgrade jackson to 2.18.0
ci: add timeout to the CI workflow
chore(release): 2.1.0
```

A breaking change marks the type with `!` (`feat(core)!: ...`) or adds a `BREAKING CHANGE:` footer.
It makes the next release a MAJOR one.

### Pull Requests

- Every branch is merged through a PR. A ruleset on `master` rejects direct pushes, force pushes
  and deletion.
- PRs are squash merged, the only merge method enabled. The PR title becomes the commit on
  `master` and the changelog entry, so it must follow Conventional Commits. `pr-title.yml` checks it.
- `Build & Test` (`ci.yml`) and `Conventional Commit title` (`pr-title.yml`) must pass before merging.

### Repository settings

Configured on GitHub, not in files. Change them only on explicit instruction.

| Setting | Value |
|---------|-------|
| Merge methods | Squash only, commit title from the PR title, branch deleted on merge |
| Ruleset `master` | PR required, the two checks above required, linear history, no force push, no deletion. Repository admins may bypass, only inside a PR |
| Ruleset `release tags` | Tags `v*` cannot be moved or deleted |
| Releases | Immutable once published |
| Actions | GitHub-owned actions and `googleapis/release-please-action` only. The default token is read-only, workflows ask for more in `permissions:`. Actions may open PRs, which release-please needs |
| Security | Dependabot alerts and security updates, secret scanning with push protection, CodeQL default setup, private vulnerability reporting |

---

## Versioning

- All modules share a single version defined in the root `pom.xml` (`flash-parent`).
- Never change the version in child POMs. Change it only in the parent.
- Current scheme: `MAJOR.MINOR.PATCH`
  - MAJOR: breaking API changes
  - MINOR: new backward-compatible features
  - PATCH: backward-compatible bug fixes on an already-released version
- During development, master always carries a `-SNAPSHOT` version.
- **Never manually edit the project version**: versions are bumped exclusively by
  [release-please](https://github.com/googleapis/release-please), configured in
  `release-please-config.json` and `.release-please-manifest.json`.

### Release Process (for maintainers only)

1. release-please keeps a PR titled `chore(release): <version>` open. It bumps every `pom.xml` and
   `CHANGELOG.md` from the Conventional Commits merged since the last release.
2. Merging it tags `v<version>` and creates the GitHub Release. `release.yml` then builds the tag
   on JitPack and publishes the JavaDoc.
3. release-please then opens a `chore(release): <next>-SNAPSHOT` PR. Merge it to move `master`
   back to a snapshot.

PRs opened by release-please do not trigger workflows, so their checks never report. A repository
admin merges them through the ruleset bypass, which works only inside a PR.

### Publishing

Releases are served by [JitPack](https://jitpack.io/#Relism/Flash), which builds the tag with the
JDK set in `jitpack.yml`. Every module is published as `dev.relism.Flash:<artifactId>:v<version>`.
The `dev.relism` prefix comes from the DNS TXT record `git.relism.dev` pointing to
`https://github.com/Relism`; without it JitPack serves the same build as `com.github.Relism.Flash`.

JitPack rewrites the groupId and version of every published POM, but not literal values inside
`<dependencyManagement>`. Managed Flash modules are therefore declared with
`<groupId>${project.groupId}</groupId>` and `<version>${project.version}</version>`, never
`dev.relism`, so that importing `flash-parent` as a BOM works for consumers.

The JitPack repository is also declared in the root `pom.xml`, because `fpr-core`
(`dev.relism.FastPathRouter`) is published there too. To move to a newer FastPathRouter release,
change the `fpr-core` version in the root `pom.xml` in a PR, or merge the Dependabot PR that does it.

---

## Maven & Module Structure

- Root POM: `flash-parent`, which defines all dependency versions and plugin config.
- `flash` module: the core framework JAR.
- `flash-testing` module: JUnit 5 harness for testing Flash applications. Deliberately not
  under `flash-extensions/`: it is not something you `install()`, and it carries
  `junit-jupiter-api` at compile scope.
- `flash-extensions` POM: aggregator for all extension modules.
- Extensions live under `flash-extensions/flash-ext-*/`.
- When adding a new extension:
  1. Add the module to `flash-extensions/pom.xml` `<modules>`.
  2. Add the dependency to `flash-extensions/pom.xml` `<dependencyManagement>`.
  3. Add the dependency to the root `pom.xml` `<dependencyManagement>`. In both, use
     `${project.groupId}` and `${project.version}`, as explained in [Publishing](#publishing).
  4. Do **not** declare a `<version>` in the new module's POM: it inherits from the parent.
  5. Expose it as a `Module`. What it adds to other modules goes through services (`RouteHook`,
     `OpenApiContributor`, `AuthenticationMechanism`), read with `Services.all`; a contribution to
     an optional dependency is installed through `Module.ifPresent`. Services take what they need
     through their constructor; `Bound` is for what the app did not build.
  6. Document it: a `README.md` at the module root and a `docs/` directory, as described in
     [Documentation](#documentation). Add the module to the tables in the root `README.md`.

---

## Documentation

Documentation is part of the code. A change that alters behavior is not finished until the
documentation that describes it is updated in the same branch.

### Layout

| Where | What |
|-------|------|
| `README.md` | What Flash is, how to install it, the module tables, the build commands. Nothing else |
| `flash/docs/` | The core. One file per feature, `docs/README.md` is the index, `docs/http2/` holds HTTP/2 |
| `flash-testing/` | `README.md` and `docs/` |
| `flash-extensions/README.md` | What an extension is, how to depend on one, the layout of a module |
| `flash-extensions/<module>/README.md` | What the extension is, the Maven coordinates, one minimal example, a table of its docs |
| `flash-extensions/<module>/docs/` | One file per aspect of the extension, with descriptive lowercase names such as `usage.md` or `security.md` |

Every extension has both. Module `README.md` files stay short; detail goes in `docs/`.

### When to update

Update the file that owns the topic whenever a change adds, removes or alters any of:

- a public type, method, annotation or default, or the behavior behind them,
- a `ServerConfig` option, a limit, a timeout or a default value,
- a status code, error body, header or log line a caller can observe,
- a boot-time check or the error it raises,
- a dependency, a build requirement or an installation step,
- a module (new, renamed, removed or split).

Delete documentation for what was deleted. Do not leave a description of code that no longer
exists, and do not add a note that something changed: the documentation states the current behavior.

### Rules for writing it

- Every fact comes from the source. Read the code before writing a default, a limit, a status
  code or a name, and re-read it when the code changes. If you cannot verify it, leave it out.
- No benchmark numbers, test counts, tool versions or dated results. They go stale and cannot be
  checked against the source.
- English. Plain and concise. No marketing language, no filler, no em dashes.
- A file covers one aspect and stands on its own. Link to another file only when the reader needs
  it to continue, and use relative links.
- Examples are short, concrete and compile against the current API.
- Use tables for options, methods and rules. Use a mermaid diagram only for a flow or a sequence
  that prose describes badly.
- Do not document internals that application code cannot reach, unless the page is about the
  implementation (for example `flash/docs/http2/`).

---

## CI/CD Pipelines

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci.yml` | PR, push to `master` | Compile and test, required check `Build & Test` |
| `pr-title.yml` | PR opened or edited | Conventional Commit title, required check |
| `release.yml` | Push to `master` | release-please; after a release, the JitPack build and `docs.yml` |
| `docs.yml` | Called by `release.yml`, or manual | Aggregated JavaDoc to the `gh-pages` branch |

Dependabot (`.github/dependabot.yml`) opens weekly PRs for Maven and GitHub Actions updates, minor
and patch updates grouped. CodeQL runs through GitHub's default setup.

**Agents must never merge release PRs or modify version strings.**

---

## What Agents Must NOT Do

- Push directly to `master` or `gh-pages`.
- Manually edit the project `<version>` in any POM.
- Merge a release-please PR, or create, move or delete a `v*` tag.
- Add new `<repositories>` or `<distributionManagement>` entries without explicit instruction.
- Modify `.github/workflows/*.yml` files without explicit instruction.
- Commit generated files (`target/`, `*.class`, `*.versionsBackup`).
- Use `git push --force` on any branch.
- Change behavior, configuration, a public API or a module without updating its documentation.
