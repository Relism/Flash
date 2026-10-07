# flash-ext-validation-jakarta

Jakarta Validation through Hibernate Validator, as the `Validation` engine. Every constraint of the
specification works, including `@Valid` cascading, groups, custom constraints and localized
messages. Constraints are read by reflection at runtime, so the build needs nothing extra.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-validation-jakarta</artifactId>
</dependency>
```

```java
app.install(new JsonModule(), new JakartaValidation());

public record NewUser(@NotBlank String name, @Min(18) int age) {}
```

A body that breaks a rule is a `422` listing every violation.

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Installing, configuring the validator, errors |
| [docs/openapi.md](docs/openapi.md) | Which constraints appear in the OpenAPI document |
