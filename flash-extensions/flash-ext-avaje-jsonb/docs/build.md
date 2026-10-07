# Build requirements

Jsonb generates one adapter per type with an annotation processor. The generator runs in the module
that owns the types.

## Annotation processor

Once a build sets `annotationProcessorPaths` (Lombok users usually do), javac finds no processor on
the classpath, so the generator must be named there:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.avaje</groupId>
                <artifactId>avaje-jsonb-generator</artifactId>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

The version comes from `flash-parent` when it is imported as a BOM. The `<version>` is required
otherwise.

## Marking types

Every type a handler reads or answers carries `@Json`. The types its fields contain get adapters
without it. A type you cannot annotate is listed with `@Json.Import`.

```java
@Json
public record NewUser(String name, Address address) {}
```

Generated adapters live in a subpackage, so an annotated type must be visible from there: public,
and nested only in public types.

## Shaded jars

Adapters are found through `ServiceLoader`: each compiled module writes
`META-INF/services/io.avaje.jsonb.spi.JsonbExtension`. A shaded jar must merge them, or only one
module's adapters survive:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

## Failure mode

Both handler types are resolved when the app boots. A type with no generated adapter fails the boot
and names the type, instead of failing on the first request.
