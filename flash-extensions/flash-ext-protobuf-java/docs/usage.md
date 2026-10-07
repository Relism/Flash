# Using protobuf

## ProtobufHandler

```java
public abstract class ProtobufHandler<I, O> extends CodecRequestHandler<Protobuf, I, Protobuf, O> {}
```

`I` and `O` are generated message classes (`com.google.protobuf.Message`, with a static
`getDefaultInstance()`). Any other type fails the boot with `IllegalArgumentException`. `Void` as `I`
reads nothing.

## Codec behavior

- A body is read whole and parsed from the array with the message's parser.
- A body that does not parse is a `400`, `Invalid protobuf message: <reason>`.
- An answer is written into the response buffer; its serialized size is computed first, so no
  intermediate array is created.
- Content type is `application/protobuf`.

## Extensions

`new ProtobufModule(extensionRegistry)` parses messages that carry protobuf extension fields with
the given `ExtensionRegistryLite`. `new ProtobufModule(protobuf)` takes a `Protobuf` you built.

## Validation

`Protobuf` asks for a `ProtobufValidation` service, not for `Validation`. This lets one application
have a validation engine for JSON or XML and a different one for protobuf. Without a
`ProtobufValidation`, bodies are parsed and not checked; the codec logs one info line at boot. The
engine for protobuf is
[flash-ext-validation-protovalidate](../../flash-ext-validation-protovalidate/README.md).

A `ProtobufValidation` that was added as a plain `Validation` (or two registered) fails the boot.

## Direct use

`Protobuf` is a service with `read(request, type)`, `write(response, value)` and `contentType()`:

```java
Order order = protobuf.read(request, Order.class);
return protobuf.write(response, reply);
```
