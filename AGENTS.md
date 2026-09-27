# Flash — Agent Guidelines

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

### Commit Messages — Conventional Commits

Format: `<type>(<scope>): <short description>`

| Type | When to use |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code change without feature/fix |
| `test` | Adding or updating tests |
| `docs` | Documentation only |
| `chore` | Build, deps, tooling — no production code |
| `ci` | Changes to GitHub Actions workflows |

Allowed scopes: `core`, `testing`, `ext-serde`, `ext-jackson`, `ext-avaje-jsonb`, `ext-openapi`, `ext-security`,
`ext-oidc`, `ext-routeviewer`,
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
- Never change the version in child POMs — always and only in the parent.
- Current scheme: `MAJOR.MINOR.PATCH`
  - MAJOR: breaking API changes
  - MINOR: new backward-compatible features
  - PATCH: backward-compatible bug fixes on an already-released version
- During development, master always carries a `-SNAPSHOT` version.
- **Never manually edit the version** — versions are bumped exclusively by the
  `prepare-release` GitHub Actions workflow.

### Release Process (for maintainers only)

1. Ensure `master` is green (CI passing).
2. Go to GitHub Actions → `Prepare Release` → `Run workflow`.
3. Input `version` (e.g. `2.1.0`) and `next_version` (e.g. `2.2.0`).
4. The workflow handles everything: bump, commit, tag, push.
5. The `release` workflow then triggers automatically on the tag.

---

## Maven & Module Structure

- Root POM: `flash-parent` — defines all dependency versions and plugin config.
- `flash` module: the core framework JAR.
- `flash-testing` module: JUnit 5 harness for testing Flash applications. Deliberately not
  under `flash-extensions/` — it is not something you `install()`, and it carries
  `junit-jupiter-api` at compile scope.
- `flash-extensions` POM: aggregator for all extension modules.
- Extensions live under `flash-extensions/flash-ext-*/`.
- When adding a new extension:
  1. Add the module to `flash-extensions/pom.xml` `<modules>`.
  2. Add the dependency to `flash-extensions/pom.xml` `<dependencyManagement>`.
  3. Add the dependency to the root `pom.xml` `<dependencyManagement>`.
  4. Do **not** declare a `<version>` in the new module's POM — it inherits from the parent.
  5. Expose it as a `Module`. What it adds to other modules goes through services (`RouteHook`,
     `OpenApiContributor`, `AuthenticationMechanism`), read with `Services.all`; a contribution to
     an optional dependency is installed through `Module.ifPresent`. Services take what they need
     through their constructor; `Bound` is for what the app did not build.

---

## CI/CD Pipelines

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci.yml` | Push to any branch, PR to master | Compile + test — required gate |
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
