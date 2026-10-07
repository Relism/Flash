# Requests and responses

## FlashRequest

`api.request()` returns a builder. The HTTP verb is terminal: it sends the request and returns a
`FlashResponse`.

```java
api.request()
        .header("Authorization", "Bearer " + token)
        .json("{\"name\":\"bob\"}")
        .post("/api/users")
        .expectStatus(201);
```

| Method | Effect |
|---|---|
| `header(name, value)` | Adds a header. Repeatable |
| `with(Consumer<FlashRequest>)` | Applies a customizer, such as a test kit attaching a credential |
| `body(String)`, `body(byte[])` | Sets the body. Strings are UTF-8 |
| `json(String)` | Sets the body and `content-type: application/json` |
| `get`, `post`, `put`, `patch`, `delete`, `head`, `options` | Send with that method |
| `send(method, path)` | Send any method, including `PURGE` and `QUERY` |

A path without a leading `/` gets one. The request times out after 10 seconds, and a transport failure
throws `AssertionError`.

## FlashResponse

Raw access: `status()`, `body()` (a `String`), `headers()` and `header(name)`, which is
case-insensitive and returns `null` when absent.

Assertions return the response, so they chain:

```java
api.get("/api/users")
        .expectStatus(200)
        .expectHeader("content-type", "application/json")
        .expectBodyContains("alice");
```

| Method | Asserts |
|---|---|
| `expectStatus(int)` | The status code |
| `expectHeader(name, value)` | The first value of the header |
| `expectBody(String)` | The body equals the text |
| `expectBodyContains(String)` | The body contains the text |

A failed assertion prints the request line, the status and the body.

The client is `java.net.http.HttpClient`: it decodes chunked responses itself and does not expose
connection reuse, so those details are not visible here.
