# Health checks and graceful shutdown (`app.health()` / `app.shutdown()`)

```js
app.health( { checks: { db: () => pingDatabase() } } )   // register before app.use(...) middleware
```

`app.health()` adds `GET /health/live` (200 while the process can answer
at all) and `GET /health/ready` (200 when the app should receive traffic,
otherwise 503) — the shape a load balancer or a Kubernetes
`livenessProbe`/`readinessProbe` wants. Each function in `checks` returns
`false`, or throws, when that dependency is unhealthy; readiness then
answers `503 { status: "unhealthy", failing: ["db"] }` — the check names,
never their exception text (which is logged instead). `path` changes the
`/health` prefix. Readiness also reports `503 { status: "draining" }` the
moment a shutdown begins. These are ordinary routes, so register them
before any session/CSRF/rate-limit middleware if probes shouldn't pass
through it.

`app.shutdown( { timeoutMs, drainDelayMs } )` stops the app gracefully:
readiness flips to draining straight away; after `drainDelayMs` (default
`0` — raise it to give a load balancer time to notice before requests are
refused) new requests get a `503`, every open WebSocket connection is
closed (firing its `onClose`), and requests already running get up to
`timeoutMs` to finish before the server stops regardless. It returns
`{ drained, webSocketsClosed }`; `drained` is `false` if the timeout ran
out. A long-lived response such as an SSE stream counts as in flight until
it ends, so it uses the whole timeout. `app.close()` is still the
immediate stop.

`SIGTERM`/Ctrl-C now run this too, with `app.set( "shutdownTimeoutMs", ms )`
(default `5000`) as the timeout — `0` restores the old immediate stop. In
Kubernetes:

```yaml
readinessProbe: { httpGet: { path: /health/ready, port: 3000 }, periodSeconds: 5 }
livenessProbe:  { httpGet: { path: /health/live,  port: 3000 }, periodSeconds: 10 }
terminationGracePeriodSeconds: 30    # comfortably above shutdownTimeoutMs
```
