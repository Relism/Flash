# Errors

## At boot

Each `NdjsonHandler` checks its setup when the app starts. A failure stops the start with an
`IllegalStateException`:

| Problem | Message |
|---|---|
| `NdjsonModule` is not installed | `<Handler> is an NdjsonHandler: install new NdjsonModule(Json.class) with the app's JSON codec` |
| The named codec is not installed | `NdjsonModule(<Codec>.class): no <codec class> is installed; install its module` |
| The codec does not write JSON (`application/json` or a `+json` type) | `NdjsonModule(<Codec>.class): NDJSON lines are JSON, and <codec class> writes <type>` |
| A type argument is left to a subclass | `<Handler> leaves its NdjsonHandler types open` |

## Before the first byte

Until the first line leaves, the response is still open, and a failure is an ordinary answer from
the app's error handler:

| Cause | Status |
|---|---|
| Request `Content-Type` is not NDJSON (only when `I` is not `Void`) | `415` |
| A line is not valid for the codec | `400`, `NDJSON line <n>: <codec message>` |
| A line decodes to `null` | `400`, `NDJSON line <n> is null` |
| A line is longer than 1 MiB | `413` |
| A line breaks a validation rule | `422`, as for a JSON body |
| `401`, `403`, `429` from security or the limiter | Decided before the handler runs |
| Any other exception from the handler | The error handler's answer |

`<n>` counts every line of the body from 1, empty ones included.

Lines sent before the failure but not yet flushed are discarded with it.

## After the first byte

Once lines have left, the status is sent and cannot change. A failure then cuts the response off:
on HTTP/1.1 the connection is closed without the final chunk, on HTTP/2 the stream is reset. A
client sees an incomplete response, not a short successful one.

## Line breaks in output

A value encoded with a `\n` or `\r` would break the stream into lines that are not JSON, for example
with a codec configured to pretty-print. `send` refuses it with an `IllegalStateException` naming the
handler, and every later `send` fails.
