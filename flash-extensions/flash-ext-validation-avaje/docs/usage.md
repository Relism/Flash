# Using Avaje validation

```java
app.install(new AvajeValidation());                  // Validator.builder().build()
app.install(new AvajeValidation(myValidator));       // an io.avaje.validation.Validator you configured
```

Use your own `Validator` for a locale, clock or message bundle.

`verify(value)` calls `validator.check(value)`, which collects every failure instead of throwing on
the first, and throws a `ValidationException` with one `Violation(path, message)` per failure. A
value that passes returns normally.

The engine checks a decoded type the generator wrote an adapter for, which includes a type whose
only rule is a cascade through Avaje's `@Valid`; a type that declares a constraint without an
adapter, refused by name on the first request; and any interface or abstract class. Any other type
is decoded with no validation call.
