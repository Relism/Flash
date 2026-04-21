# Model and Globals

`ViewModel` (from `flash-ext-view-core`) is the per-request model builder.

## Merge Order

Runtime merge order is:

1. all extension globals under reserved `global` namespace
2. local handler model

Handlers cannot set top-level `global`; runtime throws fail-fast to prevent namespace collisions.

## Globals

Register globals on extension setup:

```java
new ThymeleafExtension()
    .addGlobal("appName", req -> "Flash")
    .addGlobal("path", req -> req.path());
```

Template usage:

```html
<span th:text="${global.appName}"></span>
<span th:text="${global.path}"></span>
```

Keep globals cheap: no blocking I/O or heavy allocations.
