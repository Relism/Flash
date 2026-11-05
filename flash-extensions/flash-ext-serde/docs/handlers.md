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
3. Unless `I` is `Void`, the input codec prepares a `Decoder` for `I` through `Decoder.of`, so the
   installed checks apply ([codecs.md](codecs.md#checks)). When `I` is `@FromRequest`, each
   `InputBinder` service is asked for a reader of it instead, the first one given is used, and none
   fails the boot. The output codec prepares an `Encoder` for `O`.
4. `onCodecInit()` runs, for your own boot work. `onInit` itself is final.

At request time:

1. The binder's reader, or else `decoder.decode(request)`, produces `I`. A decoder reads
   `request.body().bytes()` by default; a codec may override it to parse from the stream.
2. `handle(request, response, input)` runs. The decoder's failure is a normal exception: a codec
   raises `HttpException.badRequest` for a malformed body, and a validation engine's check a
   `ValidationException` (422) for a value that breaks a rule. Neither reaches your method.
3. The answer is written, unless `null`.

### Answers that are not encoded

When `O` is `Void`, `byte[]`, a `Response` or a `CharSequence`, the value is returned as it is and
no codec writes it. This lets a typed handler answer with a prebuilt `Response`, and lets `Void`
mean no body. A `null` answer is also returned as it is.

### Inputs that are not the body

A type annotated `@FromRequest` is read from the request itself, such as its query, never from the
body, by an `InputBinder` a module registers as a service. Only such types are offered to binders:

```java
public interface InputBinder {
    Reader reader(Type type, Codec codec);           // at boot, once per handler; null when not its type
    interface Reader { Object read(Request request) throws Exception; }
}
```

`codec` is the handler's input codec, for a binder that names fields as the format does. Paging in
[flash-ext-data-core](../../flash-ext-data-core/docs/paging.md) is one: it reads `PageRequest<T>`.
OpenAPI documents no request body for a `@FromRequest` input: the operation names the type as
`requestParameters` with its input codec's names, and the contributor that describes the type says its parameters.

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
