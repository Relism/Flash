# flash-ext-validation-core

One contract for checking a value against the rules its type declares. Every value a codec decodes
passes through it, so a request that breaks a rule is a `422` and never reaches the handler. The
rules themselves come from an engine module.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-validation-core</artifactId>
</dependency>
```

Applications install an engine for each kind of type they check; each type goes to the engine that
checks it:

| Engine | Module |
|---|---|
| Jakarta Validation through Hibernate Validator | [flash-ext-validation-jakarta](../flash-ext-validation-jakarta/README.md) |
| Avaje Validator, generated at build time | [flash-ext-validation-avaje](../flash-ext-validation-avaje/README.md) |
| Protovalidate, for protobuf messages | [flash-ext-validation-protovalidate](../flash-ext-validation-protovalidate/README.md) |

```java
app.install(new JsonModule(), new JakartaValidation());
```

| Page | Content |
|---|---|
| [docs/contract.md](docs/contract.md) | `Validation`, `ValidationException`, who calls them |
| [docs/engines.md](docs/engines.md) | Writing an engine, several engines in one app |
| [docs/openapi.md](docs/openapi.md) | How rules reach the OpenAPI document |
