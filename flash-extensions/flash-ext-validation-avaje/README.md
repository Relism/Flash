# flash-ext-validation-avaje

[Avaje Validator](https://avaje.io/validator/) as the `Validation` engine. Constraints are checked
by adapters generated at build time, with no reflection at runtime. It enforces both Jakarta and
Avaje constraint annotations.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-validation-avaje</artifactId>
</dependency>
```

```java
app.install(new JsonModule(), new AvajeValidation());
```

The build must run the Avaje validator generator, and validated types carry
`io.avaje.validation.constraints.Valid`. See [docs/build.md](docs/build.md).

| Page | Content |
|---|---|
| [docs/build.md](docs/build.md) | Generator, `@Valid`, failure mode |
| [docs/usage.md](docs/usage.md) | Installing, behavior |
| [docs/openapi.md](docs/openapi.md) | Constraints in the OpenAPI document |
