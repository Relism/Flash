# flash-ext-validation-core

One contract for checking a value against the rules its type declares. Codecs call it on a body
they have just read, so a request that breaks a rule is a `422` and never reaches the handler. The
rules themselves come from an engine module.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-validation-core</artifactId>
</dependency>
```

Applications install exactly one engine for JSON and XML bodies:

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
| [docs/contract.md](docs/contract.md) | `Validation`, `ValidationException`, how codecs use them |
| [docs/engines.md](docs/engines.md) | Writing an engine, codec families |
| [docs/openapi.md](docs/openapi.md) | How rules reach the OpenAPI document |
