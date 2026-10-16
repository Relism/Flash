# flash-ext-openapi

Generates an OpenAPI 3.0.3 document from the routes the app builds, and serves it with a
documentation page. Paths, parameters, request and response types and media types are read from the
handlers; annotations add only what the code cannot say.

```xml
<dependency>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-openapi</artifactId>
</dependency>
```

```java
App app = App.create()
        .install(new JsonModule(), new OpenApiModule("/openapi", "Orders API", "1.0.0"))
        .scan("com.acme.api");
```

| Route | Content |
|---|---|
| `GET /openapi.json` | The document as JSON |
| `GET /openapi.yaml` | The same document as YAML |
| `GET /openapi/docs` | A documentation page: Swagger UI by default |

`basePath` (`/openapi` above) decides all three. Both documents are encoded on the first request and
served from that array afterwards.

| Page | Content |
|---|---|
| [docs/operations.md](docs/operations.md) | How a route becomes an operation, and the annotations |
| [docs/schemas.md](docs/schemas.md) | How types become schemas |
| [docs/ui.md](docs/ui.md) | Swagger UI, Redoc and Scalar |
| [docs/contributors.md](docs/contributors.md) | `OpenApiContributor`, for modules |
