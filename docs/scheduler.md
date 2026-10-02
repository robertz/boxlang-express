# Scheduler (`app.schedule`)

`app.schedule(intervalMs, callback, options)` registers a fixed-interval
recurring job — the `setInterval` equivalent, not a cron scheduler
(deliberately no cron-expression parsing in this pass — see the
non-goals below). Lazily creates one internal `Scheduler` instance on
the first call; an app that never calls `schedule()` pays nothing for
it.

```js
app.schedule( 60000, () => {
	cleanupExpiredSessions()
} )

app.schedule( 60000, ( job ) => {
	if ( shouldStopPolling() ) {
		job.cancel()
	}
}, { name: "poll-external-api", immediate: true } )
```

`schedule()` returns a job handle — `{ cancel(), name }` — the same
handle passed as the callback's own (optional) argument, so a job that
needs to stop itself doesn't have to hang onto the return value of
`schedule()` to do it.

`options.name` labels the job for `getScheduledJobs()` and the
`[Scheduler] job '...' threw` error log (below) — defaults to an
internal id like `job-3` if omitted. `options.immediate` runs the first
tick right away instead of waiting a full interval first. `options.
allowOverlap` (default `false`) controls what happens when a job is
still running when its next tick comes due: by default that tick is
skipped entirely (not queued, not delayed) rather than letting an
occasionally-slow job (a slow query, a network call) pile up an
unbounded number of concurrent runs — pass `allowOverlap: true` for a
job that's actually safe to run concurrently with itself.

Every job's tick is driven by one shared `ScheduledExecutorService` per
app, but each tick's actual callback runs on its own virtual thread
(the same split `app.listen()` uses for HTTP requests) — a slow job
never delays another job's tick. A thrown error inside a job is caught
and logged (`[Scheduler] job 'name' threw: ...`), never left to kill
future ticks of that job or any other.

`app.getScheduledJobs()` is an introspection getter, same pattern as
`Stomp.bx`'s `getConnections()`/`getSubscriptions()` — a struct of live
job state (`name`, `allowOverlap`, `running`), not a stable data API to
build app logic against. `app.close()` cancels every job and shuts the
scheduler down, the same teardown block that already stops the reload
watcher and the HTTP server.

A job body that never returns at all (blocks forever with no timeout of
its own) would otherwise leave that job's overlap flag stuck permanently,
silently skipping every future tick — a watchdog forces the flag open
again once a run has been "still active" for longer than 10x that job's
own interval (30s floor), so one wedged run can't stall a job forever.

Explicit non-goals for this first version: **no cron-expression
parsing** (fixed-interval only), **no persistence across restarts**
(in-memory only, single process — a restart forgets every scheduled
job), and **no missed-run catch-up** after downtime. See
[Scheduler.bx](../models/Scheduler.bx).

To run a job exactly once across a cluster of instances instead of once
per instance, see `options.clustered` under [Cluster
support](cluster.md).
