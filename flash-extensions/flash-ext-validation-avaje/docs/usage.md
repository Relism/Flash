# Using Avaje validation

```java
app.install(new AvajeValidation());                  // Validator.builder().build()
app.install(new AvajeValidation(myValidator));       // an io.avaje.validation.Validator you configured
```

Use your own `Validator` for a locale, clock or message bundle.

`verify(value)` calls `validator.check(value)`, which collects every failure instead of throwing on
the first, and throws a `ValidationException` with one `Violation(path, message)` per failure. A
value that passes returns normally.
