# Build requirements

`avaje-validator-generator` must run in the module that owns the validated types. It is a
`provided` dependency of this module, not transitive. Name it as an annotation processor in the
application's build:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.avaje</groupId>
                <artifactId>avaje-validator-generator</artifactId>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

Each validated type carries `io.avaje.validation.constraints.Valid` so an adapter is generated for
it. The constraints on its fields may be Jakarta's or Avaje's.

```java
@io.avaje.validation.constraints.Valid
public record NewUser(@jakarta.validation.constraints.NotBlank String name) {}
```

## Failure mode

A type that declares constraints but has no generated adapter is not skipped. The engine refuses it
by name when the first request for it is validated: the type is missing `@Valid`, or
`avaje-validator-generator` does not run in the module that compiles it. A constraint counts wherever
Avaje reads one: on the type, a field, a getter or a record component, on a type argument such as
`List<@NotBlank String>`, or on any of these in a superclass.

A type that declares no constraint has nothing to check and is accepted. The generator writes no
adapter for it, even when it carries `@Valid`.
