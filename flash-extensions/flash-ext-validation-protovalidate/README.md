# flash-ext-validation-protovalidate

[Protovalidate](https://github.com/bufbuild/protovalidate-java) as the `ProtobufValidation` engine.
Rules are declared in `.proto` files and checked against each message the protobuf codec reads, so
a message that breaks a rule is a `422`.

```xml
<dependency>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-validation-protovalidate</artifactId>
</dependency>
```

```java
app.install(new ProtobufModule(), new ProtovalidateValidation());
```

```proto
message PlaceReq {
  string sku = 1 [(buf.validate.field).string.min_len = 1];
  int32 quantity = 2 [(buf.validate.field).int32.gt = 0];
}
```

| Page | Content |
|---|---|
| [docs/usage.md](docs/usage.md) | Installing, errors, requirements |
| [docs/openapi.md](docs/openapi.md) | Rules in the OpenAPI document |
