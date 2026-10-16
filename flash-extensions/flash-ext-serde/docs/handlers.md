# Codec handlers

## CodecRequestHandler

```java
public abstract class CodecRequestHandler<CI extends Codec, I, CO extends Codec, O> extends RequestHandler
```

| Parameter | Meaning |
|---|---|
| `CI` | Codec that reads the request body |
| `I` | Type of the request body. `Void` reads nothing |
| `CO` | Codec that writes the response |
| `O` | Type of the answer |

You implement `O handle(Request, Response, I)`. Flash decodes the body into `I` before the call and
writes the returned `O` after it.

```java
@POST("/users")
final class CreateUser extends CodecRequestHandler<Json, NewUser, Json, User> {
    private final UserService users;
    CreateUser(UserService users) { this.users = users; }

    @Override protected User handle(Request request, Response response, NewUser body) {
        return users.create(body);
    }
}
```

Format modules provide a two-argument alias, such as `JsonHandler<I, O>`, for the case where input and
output use the same codec.

### What happens

At boot, in `onInit`:

1. The four arguments are resolved from the class's generic hierarchy. A handler that leaves any of
   them open (a type variable) fails the boot, as does a codec type that is not a `Codec`.
2. Each codec is looked up as a service with `services.get(CI)` and `services.get(CO)`. One that was
   never installed fails the boot.
3. A `Decoder` is prepared for `I`, unless it is `Void`, and the output codec prepares an `Encoder`
   for `O`.
4. `onCodecInit()` runs, for your own boot work. `onInit` itself is final.

At request time:

1. `decoder.decode(request)` produces `I`. By default this reads `request.body().bytes()`; a codec
   may override it to parse from the stream.
2. `handle(request, response, input)` runs. The decoder's failure is a normal exception: a codec
   raises `HttpException.badRequest` for a malformed body and a `ValidationException` (422) when a
   validation engine rejects it. Neither reaches your method.
3. The answer is written, unless `null`.

### Answers that are not encoded

When `O` is `Void`, `byte[]`, a `Response` or a `CharSequence`, the value is returned as it is and
no codec writes it. This lets a typed handler answer with a prebuilt `Response`, and lets `Void`
mean no body. A `null` answer is also returned as it is.

## CodecDuplexHandler

```java
public abstract class CodecDuplexHandler<CI extends Codec, I, CO extends Codec, O> extends DuplexHandler
```

The same four parameters for a protocol handler. It adds no framing and no cardinality. A subclass
(for example a gRPC handler) uses the protected methods to convert messages it frames itself:

| Method | Does |
|---|---|
| `I decode(byte[] bytes, int offset, int length)` | Decode one message. Throws `IllegalStateException` when `I` is `Void` |
| `void encode(O value, ByteWriter output)` | Encode one message into a buffer. Throws `IllegalStateException` when `O` is `Void` |

## TypeArguments

`TypeArguments` reads a handler's signature at boot, for an extension that checks a handler against
something else (a protocol definition, a document) or binds a base of its own:

| Method | Returns |
|---|---|
| `TypeArguments.of(Class<?> handler, Class<?> base)` | The arguments `handler` gives `base`'s type parameters, in their order, resolved through every class between them. `IllegalStateException` when `handler` does not extend `base` with arguments |
| `TypeArguments.open(Type type)` | Whether `type` still holds a type variable |

```java
Type[] types = TypeArguments.of(CreateUser.class, CodecRequestHandler.class); // Json, NewUser, Json, User
```

## Rules for the generic signature

- Declare the arguments on a class in the hierarchy. A chain such as
  `Handler<T> extends CodecRequestHandler<Json, T, Json, T>` and `Concrete extends Handler<User>` is
  resolved to `User`.
- Parameterized message types are supported: `JsonHandler<NewUser, List<User>>`.
- A type variable left unresolved at the concrete class fails the boot with the handler's name.
