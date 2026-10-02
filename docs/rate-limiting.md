# Rate limiting (`boxExpressRateLimit()` / `RateLimit`)

Fixed-window rate limiting, mirroring the npm
[`express-rate-limit`](https://github.com/express-rate-limit/express-rate-limit)
package's most commonly used options:

```js
app.use( boxExpressRateLimit() )                                     // 100 req/min per req.ip
app.use( "/login", boxExpressRateLimit( { windowMs: 15 * 60000, max: 5 } ) )  // 5 req/15min, scoped to one route
```

Each call creates its own counters — call it more than once to give
different routes independent limits, e.g. a strict one scoped to
`"/login"` alongside a looser one applied globally. Sets the draft-standard
`RateLimit-Limit`/`RateLimit-Remaining`/`RateLimit-Reset` headers (disable
with `{ standardHeaders: false }`), and responds `429` with `Retry-After`
once a key's count exceeds `max` within `windowMs`:

```js
app.use( boxExpressRateLimit( {
	windowMs: 60000,                              // 1 minute window
	max: 20,                                      // 20 requests per window per key
	keyGenerator: ( req ) => req.session.userId ?: req.ip,  // key on something other than IP
	message: { error: true, message: "Slow down." }
} ) )
```

Fixed window, not a sliding one or a token bucket: each key's counter
resets to 0 the first time it's hit after its window expires, the same
trade-off most minimal in-memory rate limiters make — a client can get up
to 2x `max` requests through right at a window boundary, in exchange for
O(1) bookkeeping per request instead of a timestamp log per key. The
default key is `req.ip` — same caveat as `req.ip` itself applies: behind a
reverse proxy without `app.set("trust proxy", 1)` (or a list of the proxies' addresses), every request shares
the proxy's own IP as the key, rate-limiting the whole app together rather
than per client. By default the store is in-memory on each `RateLimit`
instance (same trade-off as `Session`'s default `MemoryStore` — fine for a
single-process app, not a cluster).

To share the count across instances, name a registered BoxLang cache:

```js
app.use( "/login", boxExpressRateLimit( { max: 5, windowMs: 15 * 60000, cache: "shared" } ) )
```

`cache` takes the same `fallback`/`requireDurable` options and degrades the
same way as [durable sessions](sessions.md#durable-sessions-boxexpresscachestore--cachestore): with no durable cache it counts per
process, with a warning, and never fails the request. `store: myStore` (any
object with `hit( key, windowMs )` returning `{ count, resetAt }`) plugs in
your own. The read-then-write against the cache isn't atomic, so under
heavy concurrency a limit can run a few requests over, never far under,
and every request costs a cache round trip — a database call for a
`JDBCStore` — so use it on routes that matter (login, signup) rather than
as a blanket limit.
