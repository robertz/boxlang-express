# Observability: request IDs and metrics (`app.set("requestId")` / `app.metrics()`)

**Request IDs.** `app.set( "requestId", true )` gives every request an id:
it's available as `req.id`, echoed on the response as `X-Request-Id`, and
added to the request log line and the unhandled-error log line, so one
request can be followed across log lines and across nodes behind a load
balancer. A well-formed `X-Request-Id` from the client (up to 128
characters of letters, digits, `.`, `_` and `-`) is reused, so an id
minted at the edge carries through; anything else is replaced with a
generated one, since it's client-controlled and ends up in logs and a
response header. `app.set( "requestIdHeader", "X-Correlation-Id" )`
renames the header. Off by default, so existing log output is unchanged.

```
[2026-09-20 21:02:38] [checkout-7f3a] GET /hello 127.0.0.1
```

**Metrics.** `app.metrics()` adds a Prometheus-format endpoint (default
`GET /metrics`) and starts counting requests — call it before
`listen()`, and before `app.use()` if scrapes shouldn't pass through
sessions or rate limiting:

```js
app.metrics( { token: getSystemSetting( "METRICS_TOKEN" ), sources: [ stomp ] } )
```

| Metric | Type | Meaning |
|---|---|---|
| `boxexpress_http_requests_total{class}` | counter | requests by status class (`2xx`, `4xx`, …) |
| `boxexpress_http_request_duration_seconds_sum` / `_count` | summary | total handling time and count, for averages and rates |
| `boxexpress_http_active_connections`, `_active_requests` | gauge | open connections; requests in flight |
| `boxexpress_http_bytes_received_total`, `_bytes_sent_total` | counter | from Undertow |
| `boxexpress_websocket_connections` | gauge | open `app.ws()` connections |
| `boxexpress_draining` | gauge | `1` once `app.shutdown()` has begun |
| `boxexpress_scheduler_job_runs_total{job}`, `_failures_total`, `_skipped_total`, `_running` | counter / gauge | per scheduled job |
| `boxexpress_scheduler_job_last_success_timestamp_seconds{job}`, `_last_duration_seconds` | gauge | absent until the job has succeeded once |
| `boxexpress_cluster_peers`, `boxexpress_cluster_is_manager` | gauge | with clustering on; `is_manager` appears once an election has been checked, and reports the last check rather than triggering one |
| `boxexpress_stomp_connections`, `_subscriptions`, `_messages_published_total`, `_messages_delivered_total` | gauge / counter | for a broker passed in `sources` |

`sources` takes any object with a `getMetrics()` method returning
`{ name, type, help, samples: [ { labels, value } ] }` — so your own code
can add metrics to the same endpoint. A source that throws is logged and
skipped rather than failing the scrape. `token` requires
`Authorization: Bearer <token>` (compared in constant time); the numbers
are operational detail, so don't leave the endpoint open on a public
listener. Requests made through `app.inject()` aren't counted. There are
deliberately no per-route latency histograms — raw paths would blow up the
number of series, and bucket boundaries are an app-specific choice.

## Access log

By default each request prints one line as it *arrives* — method, path
and client address. That tells you a request came in, but not how it
went. Three other settings log each request once it's *answered*, with
its status and how long it took:

| `app.set( "log", ... )` | Output |
|---|---|
| `true` (default) | A line on arrival: `[2026-11-15 12:00:00] GET /users/42 127.0.0.1` |
| `"access"` | A line on completion: `[2026-11-15 12:00:00] GET /users/42 200 12.5ms 340b 127.0.0.1` |
| `"json"` | One JSON object per line on completion, for a log collector |
| a function | Called with the entry on completion; prints nothing itself |
| `false` | Nothing |

```js
app.set( "log", "json" )
// {"time":"2026-11-15T12:00:00-05:00","method":"GET","path":"/users/42","status":200,
//  "durationMs":12.5,"bytes":340,"ip":"127.0.0.1","userAgent":"curl/8.4.0","referer":"","id":"..."}
```

The entry's fields: `time` (ISO 8601), `method`, `path`, `status`,
`durationMs`, `bytes` (body bytes sent), `ip`, `userAgent`, `referer`,
plus `id` when request IDs (above) are on and `timedOut: true`
for a request that hit its [timeout](limits.md#request-timeout). `status`
is what the client actually received, including the framework's own
`404`, `500`, `413` and `503` responses.

A function gets the same entry, and the request and response, to send
wherever you like — or to add fields of your own:

```js
app.set( "log", ( entry, req, res ) => {
	entry.user = req.session.userId ?: ""
	writeLog( text = JSONSerialize( entry ), log = "access" )
} )
```

Things worth knowing:

- **`path` never includes the query string.** Query strings carry tokens
  and search terms often enough that they stay out of the log by default.
  A function sink can read `req.originalUrl` if you want them.
- **Control characters can't forge a line.** `"access"` prints them as
  `?`, and JSON escapes them.
- **A streamed response is logged when it ends**, so an SSE connection
  open for an hour logs one entry with a duration of an hour.
- **WebSocket connections aren't logged**; they don't pass through the
  request pipeline.
- A sink that throws is reported on stdout and otherwise ignored — the
  response has already been sent.
- Printing is a synchronized write. `"access"` and `"json"` cost the same
  one line per request as the default.
