# Using Prometheus

`PrometheusModule` installs `MicrometerModule` with a `PrometheusMeterRegistry` and serves the
scrape endpoint.

| Constructor | Registry | Path |
|---|---|---|
| `PrometheusModule()` | New, `PrometheusConfig.DEFAULT` | `/metrics` |
| `PrometheusModule(String path)` | New, `PrometheusConfig.DEFAULT` | `path` |
| `PrometheusModule(PrometheusMeterRegistry, String path)` | Yours | `path` |

`GET <path>` answers the exposition format with
`Content-Type: text/plain; version=0.0.4; charset=utf-8`. The text is written straight to the
connection with `Response.streaming`.

The endpoint is excluded from `http.server.requests`, so scraping does not count itself. The
registry is a service (`MeterRegistry`), so your code can take it as a constructor parameter and add
meters.

The endpoint has no authentication. Expose it on a private listener or restrict it by network, or
put middleware in front of it with `use`.
