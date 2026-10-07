# Configuration

## The mapper

`new JsonModule()` builds a mapper that:

- discovers Jackson modules on the classpath (`findAndAddModules`), which includes Java Time when
  `jackson-datatype-jsr310` is present, as it is through `flash-ext-jackson-core`,
- writes dates as ISO strings (`WRITE_DATES_AS_TIMESTAMPS` disabled).

Everything else is Jackson's default. In particular, unknown properties in a request body are a
failure (`FAIL_ON_UNKNOWN_PROPERTIES`) and null properties are written.

To decide otherwise, pass your own mapper:

```java
ObjectMapper mapper = JsonMapper.builder()
        .findAndAddModules()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .serializationInclusion(JsonInclude.Include.NON_NULL)
        .build();

app.install(new JsonModule(mapper));
```

The mapper is used as given. Configure it completely before installing the module.

## Reading the mapper

`json.mapper()` returns the `ObjectMapper` for work outside a request body, such as parsing a stored
document.

## OpenAPI

When `flash-ext-openapi` is on the classpath, field names, ignored fields and read/write-only access
from Jackson annotations appear in the document. See
[flash-ext-jackson-core](../../flash-ext-jackson-core/docs/openapi.md).
