# flash-extensions

The optional modules of Flash. Each one is a separate artifact you add next to `flash`. This
directory holds them and `pom.xml`, the aggregator that builds them and manages their versions.

An extension is installed into an `App` as a `Module`:

```java
App app = App.create()
        .install(new LimiterModule());
```

What an extension adds to another one goes through services, such as `RouteHook`,
`OpenApiContributor` or `AuthenticationMechanism`, so installing two extensions never requires
wiring them together by hand.

## Using an extension

Every extension has the groupId `dev.relism.Flash` and the same version as `flash`. With
`flash-parent` imported, as shown in the [root README](../README.md#installation), declare it
without a version:

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-limiter</artifactId>
</dependency>
```

The [module tables](../README.md#modules) in the root README list every extension with a link to
its README.

## Layout of an extension

| Path | Content |
|---|---|
| `flash-ext-<name>/pom.xml` | The module POM, with no `<version>`: it inherits the one of `flash-parent` |
| `flash-ext-<name>/README.md` | What the extension is, its coordinates, one minimal example, a table of its docs |
| `flash-ext-<name>/docs/` | One file per aspect of the extension |

How to add a new one is described in [AGENTS.md](../AGENTS.md#maven--module-structure).
