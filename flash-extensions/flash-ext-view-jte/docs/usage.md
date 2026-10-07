# Using jte

## Handlers and templates

`@Template("pages/home.jte")` (from `dev.relism.flash.ext.view.jte`) names the template relative to the
template root. It is inherited, so a base class can name a layout that subclasses fill. A blank
name fails the boot.

Model values are passed to the template by name. The template declares `@param` for each one it uses.

## Globals

Globals are merged flat into the model. A template declares one `@param` for each global it uses,
named like the global. A template that declares none does not see them. A model key equal to a
global's fails the render with `ViewModel key '...' conflicts with a registered global`.

## Settings

| Method | Default | Meaning |
|---|---|---|
| `templateRoot(String)` | `templates` | Where templates are read in dev: a directory at that path if one exists, otherwise the classpath |
| `contentType(gg.jte.ContentType)` | `Html` | `Html` escapes output and answers `text/html`. `Plain` answers `text/plain` |
| `precompiled(boolean)` | `true` outside dev, `false` in dev | Whether templates were compiled at build time |
| `classes(Path)` | `jte-classes` | Where compiled classes are written in dev and read otherwise |
| `statics(String)` | `/static` | Where the classpath's `static/` directory is served. `null` serves none |
| `global(key, resolver)` | none | See above |

Dev is [`ServerConfig.dev`](../../../flash/docs/config.md#development-mode).

## Compile modes

| Mode | Behavior |
|---|---|
| Dev (default when `dev` is on) | Templates are compiled when first used, from the directory or classpath at `templateRoot`, and recompiled on change. Compiled classes go to `classes` |
| Precompiled (default otherwise) | Templates were compiled during the build with jte's Maven plugin. If the `classes` directory exists it is read, otherwise the classes on the classpath are used |

A missing precompiled template is a runtime error of jte. Configure jte's Maven plugin
(`jte-maven-plugin`, goal `precompile`) in the application's build.

## Static files

If the classpath contains a `static/` directory, `GET <statics>/**` serves it with core's
[`StaticFiles`](../../../flash/docs/static-files.md): files are read once at boot, a `.gz` beside a file
is sent to clients that accept gzip, a name with a hex content hash is cached for a year and others
revalidate by `ETag`. There are no byte ranges, so serve large media elsewhere.
