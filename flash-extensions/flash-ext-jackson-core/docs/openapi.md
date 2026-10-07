# OpenAPI contribution

`JacksonDocs.module()` registers an `OpenApiContributor` that tells the OpenAPI document what Jackson
annotations say about a field. The format modules install it. It is registered only when
`flash-ext-openapi` is on the classpath, through `Module.ifPresent`, so a format works without
OpenAPI.

| Annotation | Effect on the schema |
|---|---|
| `@JsonProperty("name")` | The property is named `name` |
| `@JsonProperty(access = READ_ONLY)` | `readOnly: true` |
| `@JsonProperty(access = WRITE_ONLY)` | `writeOnly: true` |
| `@JsonProperty(required = true)` | The property is listed under `required` |
| `@JsonIgnore` | The property is omitted |
| `@JsonIgnoreProperties({"a", "b"})` on the class | Those properties are omitted |

Only fields are inspected.
