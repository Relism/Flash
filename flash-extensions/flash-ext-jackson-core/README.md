# flash-ext-jackson-core

The shared base of the Jackson format modules. `JacksonCodec` wraps one `ObjectMapper` as a serde
`Codec`. `flash-ext-jackson-json` and `flash-ext-jackson-xml` are two instances of it. Install one of
those; use this module directly only to add another Jackson data format.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-jackson-core</artifactId>
</dependency>
```

| Page | Content |
|---|---|
| [docs/codec.md](docs/codec.md) | `JacksonCodec`: reading, writing, errors, validation |
| [docs/openapi.md](docs/openapi.md) | What Jackson annotations contribute to the OpenAPI document |
| [docs/new-format.md](docs/new-format.md) | Adding a Jackson data format |
