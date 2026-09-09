# Model And Globals

`ViewModel` is the per-request data container.

## POJO-First Pattern

Keep page state in a POJO and pass it as a single key:

```java
return ViewModel.empty()
    .with("page", new HomePage("Flash + jte", "elorc"))
    .with("build", "dev");
```

Then type it in template:

```jte
@import com.example.HomePage
@param HomePage page
@param String build
```

## Globals

Register globals in extension setup:

```java
new JteExtension().addGlobal("appName", req -> "Flash")
```

Globals are merged **flat** into the model — same level as local `ViewModel` keys, not
under a `global.*` namespace. jte templates are statically typed, so each template declares
one `@param` per global it actually uses, named exactly like the global key:

```jte
@param String appName

<span>${appName}</span>
```

A template that doesn't declare `appName` simply never sees it — no need to declare every
global on every page, only the ones a given template actually uses.

A local `ViewModel` key that collides with a registered global name fails fast with
`IllegalStateException` at render time, so a typo can't silently shadow a global.

Every registered global is still resolved on **every** render regardless of whether the
target template declares it, so keep resolvers cheap (no blocking I/O, no heavy allocation).
