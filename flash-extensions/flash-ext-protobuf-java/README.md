# flash-ext-protobuf-java

Binary Protocol Buffer request and response bodies through Google's `protobuf-java` runtime.
Requests are parsed directly from Flash's bounded body stream. The generated parser is resolved
when the handler is bound; the request path performs no reflection or type lookup. Responses use
the single final `byte[]` produced by protobuf, which Flash writes without another application-side
copy.

## Install

Add `flash-ext-protobuf-java`, then install its module:

```java
App.create()
    .install(new ProtobufModule())
    .scan("com.acme.handlers");
```

```java
@POST("/orders")
public final class CreateOrder
        extends ProtobufHandler<CreateOrderRequest, CreateOrderResponse> {

    @Override
    protected CreateOrderResponse handle(Request request, Response response,
                                         CreateOrderRequest body) {
        return orders.create(body);
    }
}
```

`Void` as the input reads no body. A malformed binary message is a `400`; a message rejected by
the installed validation engine is a `422`.

## Generate the Java messages

The application owns its `.proto` files and configures `protoc` in its own build. This extension
does not install or hide a Maven/Gradle code-generation plugin. Generate ordinary full-runtime Java
messages and keep the `protoc` version no newer than the `protobuf-java` runtime managed by the
Flash BOM.

The canonical command is:

```shell
protoc -I=src/main/proto \
  --java_out=target/generated-sources/protobuf/java \
  src/main/proto/orders.proto
```

In a real build, bind the equivalent command through the Maven or Gradle protobuf plugin and add
its output directory as a generated source root. Follow Google's current
[protobuf-java documentation](https://github.com/protocolbuffers/protobuf/blob/main/java/README.md)
and [Java generated-code guide](https://protobuf.dev/reference/java/java-generated/); code
generation deliberately remains an application build concern.

## Validation

Install `flash-ext-validation-protovalidate` when the `.proto` descriptors carry Protovalidate
rules:

```java
app.install(new ProtobufModule(), new ProtovalidateValidation());
```

The codec calls the installed `ProtobufValidation` after parsing; handlers never call it. It asks for that
type and not for the `Validation` JSON and XML bodies use, so an application serving both installs Jakarta or
Avaje for those and Protovalidate for protobuf, each checking only its own bodies.

The boot refuses what would otherwise be silent: an engine for protobuf added as plain `Validation` (which
this codec would never ask), two `ProtobufValidation`s, and Protovalidate without this module. With no
`ProtobufValidation` at all, protobuf bodies are parsed and not checked, and the codec says so once at boot.
See [Families](../flash-ext-validation-core/README.md#families) for the rule behind it.

See [flash-ext-validation-protovalidate](../flash-ext-validation-protovalidate) and the
[Protovalidate documentation](https://protovalidate.com/) for declaring rules in `.proto` files.

## OpenAPI

When `flash-ext-openapi` is present, the module derives schemas from protobuf descriptors rather
than reflecting over generated implementation fields. Nested and recursive messages, maps,
repeated fields, enums, scalar formats, proto2 required fields and the standard well-known types
are represented automatically. The operation media type is `application/protobuf`.

## Configuration

The default empty extension registry is correct for ordinary generated messages. Applications
using protobuf extension fields can provide their registry:

```java
app.install(new ProtobufModule(extensionRegistry));
```

The module targets the full `protobuf-java` runtime. `protobuf-javalite`, protobuf JSON and gRPC
are separate transports/runtime choices and are not implied by this module.
