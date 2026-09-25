# flash-ext-validation-avaje

Avaje Validator: the same constraints, checked by adapters generated at build time. No reflection at
runtime, which is the reason to choose it — and the reason it asks something of the build.

```java
app.install(new JsonExtension())
   .install(new AvajeValidationExtension());
```

## What the application's build must do

The processor runs in the module that owns the validated types, and the parent's
`annotationProcessorPaths` hides anything on the classpath — so it is named, not merely depended on:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.avaje</groupId>
                <artifactId>avaje-validator-generator</artifactId>
                <version>${avaje.validator.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

Each validated type carries `io.avaje.validation.constraints.Valid` so an adapter is written for it.
It has to be Avaje's own: jakarta's `@Valid` cannot go on a type, its targets are method, field,
constructor, parameter and type use. The constraints themselves stay jakarta's.

```java
@Valid                                              // io.avaje.validation.constraints.Valid
public record Address(@NotBlank String street) {}   // jakarta.validation.constraints.NotBlank

@Valid
public record CreateUser(@NotBlank @Size(max = 8) String name,
                         @Email String email,
                         @jakarta.validation.Valid Address address) {}   // cascades
```

A type whose adapter is missing is not skipped quietly: the engine refuses it by name — `No
ValidationAdapter for …` — on the first request that carries it. Run the suite once over every route
after switching to this engine, and nothing is left to find in production.

## What the document says

The jakarta constraints this engine enforces, plus the ones that are its own: `@Length`, `@URI`,
`@UUID`, `@Range`, `@Positive` and the rest of its family become `maxLength`, `format: uri`,
`format: uuid`, `minimum`, `exclusiveMinimum` and the required list. Each engine documents what it
actually does, so nothing is inherited from the other one by accident.

## Notes

- `new AvajeValidationExtension(validator)` takes a validator configured by hand: a locale, a clock,
  a message bundle.
- Failures come back through `check`, not `validate`: every broken rule in one 422, and no exception
  used as a signal.
- Two validation engines in one application is a boot failure: see
  [`flash-ext-validation-core`](../flash-ext-validation-core).
