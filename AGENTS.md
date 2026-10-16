# Flash: Agent Guidelines

This document defines the conventions and rules that all agents (AI or human) must follow
when working on this repository. Read it entirely before making any change.

---

## Git Workflow

### Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| New feature | `feature/<scope>/<short-description>` | `feature/ext-oidc/pkce-support` |
| Bug fix | `fix/<scope>/<short-description>` | `fix/core/router-npe` |
| Hotfix on released version | `hotfix/<version>/<short-description>` | `hotfix/2.0.1/auth-bypass` |
| CI/infra changes | `feature/ci/<short-description>` | `feature/ci/add-snapshot-workflow` |

Rules:
- Always branch from `master`.
- Branch names are lowercase, words separated by `-`.
- Never push directly to `master`.
- Never create `develop`, `release/*`, or any other long-lived branch.

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
ci: add timeout to snapshot workflow
chore(release): 2.1.0
```

### Pull Requests

- Every branch must be merged via PR, never with a direct push.
- PR title must follow Conventional Commits format.
- CI (`ci.yml`) must be green before merging.
- Squash merge is preferred for `feature/*` and `fix/*` to keep history clean.
- Merge commit is preferred for hotfixes (preserves the fix commit intact).

---

## Versioning

- All modules share a single version defined in the root `pom.xml` (`flash-parent`).
- Never change the version in child POMs. Change it only in the parent.
- Current scheme: `MAJOR.MINOR.PATCH`
  - MAJOR: breaking API changes
  - MINOR: new backward-compatible features
  - PATCH: backward-compatible bug fixes on an already-released version
- During development, master always carries a `-SNAPSHOT` version.
- **Never manually edit the version**: versions are bumped exclusively by the
  `prepare-release` GitHub Actions workflow.

### Release Process (for maintainers only)

1. Ensure `master` is green (CI passing).
2. Go to GitHub Actions → `Prepare Release` → `Run workflow`.
3. Input `version` (e.g. `2.1.0`) and `next_version` (e.g. `2.2.0`).
4. The workflow handles everything: bump, commit, tag, push.
5. The `release` workflow then triggers automatically on the tag.

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
  3. Add the dependency to the root `pom.xml` `<dependencyManagement>`.
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
| `README.md` | What Flash is, the module tables, the build commands. Nothing else |
| `flash/docs/` | The core. One file per feature, `docs/README.md` is the index, `docs/http2/` holds HTTP/2 |
| `flash-testing/` | `README.md` and `docs/` |
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
| `ci.yml` | Push to any branch, PR to master | Compile and test, required gate |
| `snapshot.yml` | Push to `master` | Deploy `-SNAPSHOT` to `maven.relism.dev/snapshots` |
| `prepare-release.yml` | Manual `workflow_dispatch` | Bump version, commit, tag, push |
| `release.yml` | Push of tag `v*` | GPG sign, deploy to `/releases`, JavaDoc to GitHub Pages, GitHub Release |

**Agents must never manually trigger `prepare-release` or modify version strings.**

---

## What Agents Must NOT Do

- Push directly to `master` or `gh-pages`.
- Manually edit `<version>` tags in any POM.
- Add new `<repositories>` or `<distributionManagement>` entries without explicit instruction.
- Modify `.github/workflows/*.yml` files without explicit instruction.
- Commit generated files (`target/`, `*.class`, `*.versionsBackup`).
- Use `git push --force` on any branch.
- Change behavior, configuration, a public API or a module without updating its documentation.
