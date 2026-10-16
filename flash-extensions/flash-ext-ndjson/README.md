# flash-ext-ndjson

Newline-delimited JSON streams: a request read one line at a time, a response written one line at a
time. Lines are read and written with the app's JSON codec, chosen when the module is installed.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-ndjson</artifactId>
</dependency>
```

```java
App.create().install(new JsonModule(), new NdjsonModule(Json.class)).scan("com.acme");

@POST("/import")
final class Import extends NdjsonHandler<Row, Result> {
    @Override protected void handle(Request req, NdjsonInput<Row> in, NdjsonOutput<Result> out) throws Exception {
        for (Row row; (row = in.next()) != null; ) out.send(rows.save(row));
    }
}
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Module, handler, reading and writing lines, OpenAPI |
| [docs/errors.md](docs/errors.md) | Boot checks, answers before and after the first byte |
