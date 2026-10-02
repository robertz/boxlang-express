# App

- `app.get/post/put/patch/delete/head/all(path, ...handlers)`
- `app.use(handler)` / `app.use(path, handler)` / `app.use(path, router)`
- `app.param(name, callback)` — see [Route params](routing.md#route-params-appparamname-callback)
- `app.route(path)` — see [Chainable routes](routing.md#chainable-routes-approutepath)
- `app.set(name, value)` / `app.getSetting(name)`
- `app.locals` — a plain struct merged into every `res.render()` call's data;
  see [Views](views.md)
- `app.listen(port, callback, options)` — starts the server and blocks the calling thread by default
- `app.close()` — stops the server, and breaks a blocked `listen()` (from any thread)
- `app.getConnectorStatistics()` — live HTTP-layer metrics straight from
  Undertow's listener: active connections/requests, total request count,
  bytes sent/received, error count, processing time. `null` before
  `listen()` has run or after `close()`. Useful for a server-monitoring
  dashboard alongside JVM/OS-level metrics (memory, CPU, GC), which don't
  see anything at the HTTP layer.
- `app.ws(path, callback)` — WebSocket routes; see
  [WebSockets](websockets.md).
- `app.schedule(intervalMs, callback, options)` / `app.getScheduledJobs()`
  — fixed-interval recurring jobs; see [Scheduler](scheduler.md).
- `app.getClusterManager()` — cross-process peer discovery, manager
  election, and the STOMP relay mesh; see [Cluster
  support](cluster.md).

Node keeps a CLI process alive via its event loop; BoxLang's CLI runtime has
no equivalent, so unlike Express, `listen()` blocks the calling thread by
default rather than requiring every script to remember its own keep-alive
loop — a plain `boxlang server.bxs` just stays up. Pass `{ block: false }` as
the third argument to get the old non-blocking behavior back (this project's
own test suite uses that, since it needs to keep running setup code — and
eventually call `close()` — after `listen()` returns):

```js
app.listen( 3000, ( port ) => {
	println( "listening on #port#" )
} )
// unreachable until the server stops — that's the point

app.listen( 3000, ( port ) => { ... }, { block: false } )
// returns immediately, same as Express's listen()
```

`close()` works either way, and — since it just flips a flag `listen()`'s
loop polls every second — also works when called from a *different* thread
than the one blocked in `listen()`, e.g. a `/shutdown` route handler running
on its own virtual thread.

`options.backlog` sets Undertow's TCP accept queue depth (default `1024`,
passed through to `org.xnio.Options.BACKLOG`) — how many pending connections
the OS will hold before refusing new ones outright, independent of how fast
requests are actually being handled. A small backlog is enough that a burst
of concurrent connections can get refused with a reset instead of queued,
even while request handling itself stays fast — confirmed with a real load
test against an earlier JDK-backed version of this server, which is why the
default here is `1024` rather than left unset. Rarely needs touching — a
reverse proxy in front (already required, since BoxExpress never terminates
TLS itself) will usually queue connections before this limit is ever
reached — but it's there for a direct-exposure deployment or a deliberately
higher ceiling.

Every request gets a line on stdout as soon as it's received —
`[2026-08-10 10:45:41] GET /users/42 127.0.0.1`. Turn it off with
`app.set("log", false)` — useful for a high-throughput deployment that
doesn't want a synchronous stdout write on every request.

## More on the app object

- [Testing your app without a server](testing.md) — `app.inject()`
- [Observability](observability.md) — request IDs and `app.metrics()`
- [Health checks and graceful shutdown](health-and-shutdown.md) — `app.health()` / `app.shutdown()`
- [Auto-restart on file change](auto-restart.md) — `app.set("reloadOnChange", true)`
