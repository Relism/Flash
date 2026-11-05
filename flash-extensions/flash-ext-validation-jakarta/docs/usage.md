# Using Jakarta validation

```java
app.install(new JakartaValidation());                       // default validator factory
app.install(new JakartaValidation(myConfiguredValidator));  // your own jakarta.validation.Validator
```

Pass your own `Validator` to set a message interpolator, a clock or a parameter name provider.

## Behavior

`verify(value)` runs `validator.validate(value)`. When there are violations it throws a
`ValidationException` with one `Violation(path, message)` per `ConstraintViolation`, where `path` is
the violation's property path (`address.city`) and `message` the interpolated message.

A nested object is validated only when its field carries `@Valid`, as in the specification.

The engine checks a decoded type that the validator reports as constrained (a constraint or a
`@Valid` cascade anywhere on it), and any interface or abstract class, whose values may be of a
subclass that is. Any other type is decoded with no validation call.

## Dependencies

The module brings Hibernate Validator and `expressly` (the expression language implementation it
needs for message interpolation).
