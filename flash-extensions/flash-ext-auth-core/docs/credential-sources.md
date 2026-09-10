# Writing a credential source

A `CredentialSource` is the only thing that stands between a request and its claims. Everything
else in this module — annotations, policy, matching, the holder — works the same regardless of
which one is installed.

```java
public interface CredentialSource {
    Map<String, Object> authenticate(Request req, Response res);
    Map<String, Object> peek(Request req);
    default String insufficientScopeChallenge(String[] requiredScopes) { return null; }
}
```

## `authenticate` has three outcomes, and the last two are not the same

| Return | Means | The middleware then |
|---|---|---|
| claims | a valid credential was presented | publishes them and calls the handler |
| `null` | **no** credential, and the source has already answered the request | stops, writes nothing more |
| throws `HttpException` | a credential **was** presented and is invalid | propagates it |

Flattening the last two is the single easiest way to get this wrong. "No session, send the browser
to the sign-in page" and "this token is forged" are different answers, and a caller can tell:
the first is a `302` to a login screen, the second a `401` the client must not retry blindly.

A source that returns `null` owns the response by then — it has redirected, or written a `401` with
its own `WWW-Authenticate` header. A source that throws sets any challenge header it owes *before*
throwing, because the exception unwinds past the middleware.

`peek` is the same resolution with every rejection removed: no throwing, no redirecting, `null`
when there is nothing valid. It backs `@Authenticated(optional = true)`, where an anonymous caller
is a normal outcome. Never make `peek` refresh state that `authenticate` would not have.

## A minimal source

```java
public final class ApiKeySource implements CredentialSource {

    private final Map<String, Map<String, Object>> keys;   // key -> claims

    @Override
    public Map<String, Object> authenticate(Request req, Response res) {
        String key = req.header("X-Api-Key");
        if (key == null) {
            res.header("WWW-Authenticate", "ApiKey realm=\"api\"");
            throw HttpException.unauthorized();
        }
        Map<String, Object> claims = keys.get(key);
        if (claims == null) throw HttpException.unauthorized();   // presented and wrong
        return claims;
    }

    @Override
    public Map<String, Object> peek(Request req) {
        String key = req.header("X-Api-Key");
        return key != null ? keys.get(key) : null;
    }
}
```

This one never returns `null` from `authenticate` — it has no sign-in flow to redirect into, so
"absent" and "invalid" both mean `401`. That is a legitimate shape; the three outcomes are what
the interface *allows*, not a checklist.

## Claims are yours to shape

The claims map is whatever your mechanism produces. `Claims` reads a few conventional keys —
`sub`, `email`, `name`, `preferred_username` — so populating those makes your source work with
code written against any other. Roles and scopes are read from wherever `AuthConfig` points, so
they can live under any key you like as long as the two agree.

## Installing it

```java
public final class ApiKeyExtension implements FlashExtension {
    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.provide(ApiKeySource.class, source);
        AuthMiddleware.install(ctx, AuthConfig.builder()
                .rolesClaimPath("roles")
                .build(), source);
    }
}
```

`AuthMiddleware.install` also registers the annotation processor, so scanned handlers carrying
`@Authenticated` and friends are mounted behind your source with nothing further to do.

## One source at a time

`AuthMiddleware` is published in the context under its own type, so installing two extensions that
each call `install` leaves the last one winning — quietly. If an app genuinely needs to accept two
kinds of credential, that is one source that tries both, not two sources: the order they are tried
in, and what happens when the first rejects, are decisions that have to live somewhere explicit.
