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

Globals are available under `global` namespace in templates.

`global` is reserved and cannot be used as local model key.
