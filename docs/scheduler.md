# Scheduler (`app.schedule`)

`app.schedule(intervalMs, callback, options)` registers a recurring job
— every so many milliseconds (the `setInterval` equivalent), or at
clock times given as a [cron schedule](#cron-schedules). Lazily creates one internal `Scheduler` instance on
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
`options.clustered` runs the job on only one instance of a cluster (see
below). `options.stallThresholdMs` overrides how long a run may stay
active before it's treated as hung (see below).

`intervalMs` must be a whole number of milliseconds, 1 or more; anything
else throws `BoxExpress.InvalidArgument` and nothing is scheduled.
`schedule()` is safe to call from concurrent requests.

Every job's tick is driven by one shared `ScheduledExecutorService` per
app, but each tick's actual callback runs on its own virtual thread
(the same split `app.listen()` uses for HTTP requests) — a slow job
never delays another job's tick. A thrown error inside a job is caught
and logged (`[Scheduler] job 'name' threw: ...`), never left to kill
future ticks of that job or any other.

`app.getScheduledJobs()` is an introspection getter, same pattern as
`Stomp.bx`'s `getConnections()`/`getSubscriptions()` — a struct of live
job state (`name`, `allowOverlap`, `clustered`, `running`), not a stable data API to
build app logic against. `app.close()` cancels every job and shuts the
scheduler down, the same teardown block that already stops the reload
watcher and the HTTP server.

A job body that never returns at all (blocks forever with no timeout of
its own) would otherwise leave that job marked active permanently,
silently skipping every future tick. Once a run has been active for
longer than its stall threshold (`options.stallThresholdMs`, default 10x
the job's interval with a 30s minimum), the next tick logs it and starts
a new run in its place. If the hung run does return later, it doesn't
clear the new run's active state, so the overlap guard still holds.

A job's handle can be cancelled at any time, including from inside its
first `immediate: true` run, before `schedule()` has returned.

Metrics (see [Observability](observability.md)) report each job's runs,
failures, skipped ticks, whether it's running, and its last success.
Skipped ticks count only overlap skips; a non-manager instance passing
over a clustered job doesn't count as a skip.

Explicit non-goals: **no persistence across restarts**
(in-memory only, single process — a restart forgets every scheduled
job), and **no missed-run catch-up** after downtime. See
[Scheduler.bx](../models/Scheduler.bx).

## Cron schedules

Pass a cron expression instead of an interval to run a job at clock
times — "every night at 2", "every 15 minutes during office hours":

```js
app.schedule( "0 2 * * *", () => purgeOldUploads(), { name: "nightly-purge" } )
app.schedule( "*/15 9-17 * * MON-FRI", () => syncInventory() )
app.schedule( "0 9 1 * *", () => sendInvoices(), { timezone: "America/New_York" } )
```

Five fields, in the usual order:

| Field | Values |
|---|---|
| minute | `0`-`59` |
| hour | `0`-`23` |
| day of month | `1`-`31` |
| month | `1`-`12` or `JAN`-`DEC` |
| day of week | `0`-`7` or `SUN`-`SAT` (`0` and `7` are both Sunday) |

Each field takes `*`, a single value, a range (`9-17`), a list
(`1,15`), and a step (`*/15`, `10-40/5`, or `5/20` for "from 5, every
20"). A sixth field in front adds seconds (`*/10 * * * * *` is every ten
seconds). `@hourly`, `@daily`, `@weekly`, `@monthly` and `@yearly` are
shorthands.

When both day-of-month and day-of-week are restricted, the job runs on a
day matching *either* — `0 0 1,15 * MON` is the 1st, the 15th, and every
Monday. That's the standard cron rule, and it surprises people.

Times are in the server's timezone unless `options.timezone` names
another (`"UTC"`, `"Europe/London"`). On the day the clocks go forward, a
time in the skipped hour doesn't run that day; on the day they go back, a
time in the repeated hour runs twice. Schedule in `"UTC"` if either
matters.

An expression that's malformed, names an unknown timezone, or can never
fire (`0 0 30 2 *`) throws `BoxExpress.InvalidArgument` from
`schedule()`. Everything else works as for an interval job: the handle
and `job.cancel()`, `name`, `allowOverlap`, `clustered`, and `immediate`
(which runs the job once right away *as well as* on its schedule).
`getScheduledJobs()` reports a cron job's expression as `cron` and when
it next fires as `nextRun`.

Still true of cron jobs: nothing is persisted, and a run that was due
while the process was down is not made up afterwards. A job that must
not be missed needs a durable queue, not an in-process timer.

To run a job exactly once across a cluster of instances instead of once
per instance, see `options.clustered` under [Cluster
support](cluster.md).
