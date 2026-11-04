# OpenAPI contribution

`JacksonDocs.module(codec)` registers an `OpenApiContributor` that tells the OpenAPI document what
Jackson writes a field as. The format modules install it with their codec. It is registered only when
`flash-ext-openapi` is on the classpath, through `Module.ifPresent`, so a format works without
OpenAPI.

| Annotation | Effect on the schema |
|---|---|
| `@JsonProperty("name")`, or the mapper's naming strategy | The property has the name the codec writes it under (`Codec.wireName`) |
| `@JsonProperty(access = READ_ONLY)` | `readOnly: true` |
| `@JsonProperty(access = WRITE_ONLY)` | `writeOnly: true` |
| `@JsonProperty(required = true)` | The property is listed under `required` |
| `@JsonIgnore` | The property is omitted |
| `@JsonIgnoreProperties({"a", "b"})` on the class | Those properties are omitted |

Only fields are inspected.
