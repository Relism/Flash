# OpenAPI contribution

What the document says of a Jackson field comes from two places, both installed only when
`flash-ext-openapi` is on the classpath, so a format works without OpenAPI:

- its name, and whether it is there at all, from the codec's `wireName`, which flash-ext-serde
  reads for every codec installed through `Codec.module`;
- its direction and whether it is required, from `JacksonDocs.module()`, which the format modules
  install beside their codec.

| Annotation | Effect on the schema |
|---|---|
| `@JsonProperty("name")`, or the mapper's naming strategy | The property has the name the codec writes it under |
| `@JsonProperty(access = READ_ONLY)` | `readOnly: true` |
| `@JsonProperty(access = WRITE_ONLY)` | `writeOnly: true` |
| `@JsonProperty(required = true)` | The property is listed under `required` |
| `@JsonIgnore`, `@JsonIgnoreProperties`, `@JsonIncludeProperties`, or a field the mapper cannot see | The property is omitted |

Names and exclusions follow the mapper's own property model, mix-ins and config overrides included.
Access and `required` are read from the field's own `@JsonProperty`.
