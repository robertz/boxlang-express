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

## Options

| Option | Default | |
|---|---|---|
| `windowMs` | `60000` | Length of the window, in milliseconds |
| `max` | `100` | Requests allowed per key in each window |
| `keyGenerator` | `( req ) => req.ip` | What to count by |
| `statusCode` | `429` | Status for a limited request |
| `message` | `{ error: true, message: "Too many requests, please try again later." }` | The JSON body a limited request gets |
| `handler` | none | `( req, res, next, info ) => ...` — answers a limited request instead of sending `message` |
| `standardHeaders` | `true` | Send `RateLimit-*` and `Retry-After` |
| `maxKeys` | `100000` | Most keys the in-memory store counts separately; `0` for no cap — see [below](#how-many-keys-it-tracks) |
| `cache`, `store`, `fallback`, `requireDurable` | in-memory | Where counts are kept — see [below](#sharing-the-count-across-instances) |

### `handler`: choosing what a limited request gets

By default a limited request gets `message` as JSON, which is wrong for a
route that serves HTML: a reader over the limit on a search page would
see a raw JSON blob. `handler` is called in its place, as in
`express-rate-limit`:

```js
app.get( "/search", boxExpressRateLimit( {
	windowMs: 60000,
	max: 30,
	handler: ( req, res, next, info ) => res.render( "search/index", { retryAfter: info.resetSeconds } )
} ), ( req, res ) => {
	res.render( "search/index", { results: search.find( req.query.q ?: "" ) } )
} )
```

The status (`statusCode`, 429 unless you changed it) and, with
`standardHeaders` on, `Retry-After` are set before the handler runs, so
it only has to produce the body — the `res.render()` above goes out as a
429. `info` is:

| | |
|---|---|
| `limit` | `max` |
| `remaining` | Requests left in the window — `0` here |
| `resetSeconds` | Seconds until the window resets; the same number sent in `Retry-After` |
| `key` | What `keyGenerator` returned for this request |

The handler must end the response or call `next()`. Calling `next()` lets
the request through, still carrying the 429 status unless a later handler
sets another. It works the same with the in-memory store, `cache` and a
custom `store`. Without `handler` nothing changes. A `handler` that isn't
a function throws when the middleware is created.

## How it counts

Fixed window, not a sliding one or a token bucket: each key's counter
resets to 0 the first time it's hit after its window expires, the same
trade-off most minimal in-memory rate limiters make — a client can get up
to 2x `max` requests through right at a window boundary, in exchange for
O(1) bookkeeping per request instead of a timestamp log per key. The
default key is `req.ip` — same caveat as `req.ip` itself applies: behind a
reverse proxy without `app.set("trust proxy", 1)` (or a list of the proxies' addresses), every request shares
the proxy's own IP as the key, rate-limiting the whole app together rather
than per client. By default the store is in-memory (counted atomically, so concurrent
requests can't slip past the limit) on each `RateLimit`
instance (same trade-off as `Session`'s default `MemoryStore` — fine for a
single-process app, not a cluster).

## How many keys it tracks

The in-memory store keeps one counter per key until that key's window
expires, so a flood of requests from distinct keys — spoofed
`X-Forwarded-For` values behind a too-trusting `trust proxy`, or a
`keyGenerator` reading a header the client controls — would otherwise
grow it without limit. `maxKeys` (default `100000`) caps it: once that
many keys are being counted, a key that isn't already among them is
counted in **one shared bucket** until some expire.

- Keys already being counted are unaffected; each keeps its own count.
- New keys aren't let through uncounted, so filling the store isn't a way
  around the limit — but while it's full they are limited *together*, to
  `max` requests per window between them.
- A warning is logged, at most once a minute, while the store is full.

Reaching the cap in normal traffic means it's too low for your number of
clients per window: raise it, or use a `cache` or `store`, which `maxKeys`
doesn't apply to. `0` removes the cap. A value that isn't a whole number,
0 or more, throws `BoxExpress.InvalidArgument` when the middleware is
created.

## Sharing the count across instances

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
