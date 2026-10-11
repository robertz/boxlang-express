# Sessions (`boxExpressSession()` / `Session`)

Cookie-based sessions, mirroring [express-session](https://github.com/expressjs/session)'s
default (in-memory) behavior. The cookie carries only an opaque, unguessable
session ID — the actual data lives server-side, keyed by that ID — and is
rolling: every request through this middleware resets both the cookie's and
the stored data's expiry to `maxAge` from now (the store write can be
skipped for an unmodified session — see `resave`/`saveUninitialized` below —
in which case only the cookie's expiry actually resets).

```js
app.use( boxExpressSession() )                                // connect.sid cookie, 24h maxAge
app.use( boxExpressSession( { name: "sid", maxAge: 3600 } ) )  // custom name, 1h (seconds) maxAge

app.get( "/", ( req, res ) => {
	req.session.views = ( req.session.views ?: 0 ) + 1
	res.json( { views: req.session.views } )
} )
```

or via the underlying class:

```js
session = new bxModules.boxexpress.models.middleware.Session()
app.use( session.session( { maxAge: 3600 } ) )
```

`req.session` is a plain struct — read and write whatever you like on it, it's
saved automatically (no explicit `req.session.save()` call, since BoxLang
structs are references: the same struct instance backs the store entry).
A changed session is written to the store just before the response is
sent, so the client's next request finds it even with a database-backed
store; a change made after the response has gone out is written once the
request finishes.
`req.sessionID` is the current session's ID. Call `req.destroySession()` to
log a user out — it removes the server-side data and expires the cookie.

Call `req.regenerateSession()` when a user logs in (or gains privileges):
it moves the session's data to a new id, deletes the old one, and sends the
new cookie. Without it, a session id an attacker managed to plant in the
victim's browser before login would become an authenticated session
(session fixation).

The built-in in-memory store holds at most `maxSessions` sessions (default
`100000`); past that, new sessions aren't stored — existing ones keep
working — and a warning is logged, so a flood of cookieless requests can't
exhaust memory. With the default `saveUninitialized: true` every new
visitor counts, so set it to `false` if you can.

The default store is in-memory on the `Session` instance (so it doesn't
survive a restart and isn't shared across processes) — swap in something
durable by passing `{ store: myStore }`, an object exposing
`get(id)` / `set(id, data, maxAge)` / `destroy(id)`.

## Skipping unnecessary store writes (`resave`, `saveUninitialized`)

By default, every request through this middleware writes to the store —
even one that never reads or touches `req.session` at all. That's free
against the default in-memory store (a struct reference into a
`ConcurrentHashMap`), but a real cost against anything out-of-process: a
`boxExpressCacheStore()`-backed `JDBCStore`, Redis, etc. Two options,
mirroring [express-session](https://github.com/expressjs/session)'s own
options of the same name, turn that off:

```js
app.use( boxExpressSession( {
	store: myStore,
	resave: false,             // don't re-save an existing session that wasn't modified
	saveUninitialized: false   // don't save (or cookie) a new session that wasn't modified
} ) )
```

- **`saveUninitialized: false`** — a session created fresh for a request
  (no existing cookie) that's never modified during it isn't saved, and
  isn't given a `Set-Cookie` either — there's nothing to remember, and no
  reason to hand that visitor an identifier. This is the one that matters
  most in practice: an anonymous request that only ever *reads*
  `req.session` (a page checking whether the visitor happens to be logged
  in, say) shouldn't create a session at all, let alone write one to a
  database — including, notably, the flood of unrelated 404s a
  vulnerability scanner throws at a public site in the same second, each
  of which would otherwise mint and persist its own throwaway session.
- **`resave: false`** — an *existing* session that was loaded but never
  modified isn't re-written to the store. The cookie is still refreshed
  either way (kept simple, matching this middleware's existing rolling
  behavior) — only the store write is skipped.
- A session that *is* modified — new or pre-existing — is always saved
  regardless of either option; these only ever skip a write that would
  otherwise be a no-op.

Both default to `true` (the historical always-save behavior), so adding
this middleware — or upgrading — doesn't silently change behavior for
existing consumers. Recommended `false` for anything backed by a real
datastore.

## Durable sessions (`boxExpressCacheStore()` / `CacheStore`)

A ready-made `store` backed by BoxLang's own [cache service](https://boxlang.ortusbooks.com/boxlang-language/cache)
(the `cache()` BIF) — memory by default, or a real SQL table if the named
cache is configured with `objectStore: "JDBCStore"`:

```js
app.use( boxExpressSession( { store: boxExpressCacheStore() } ) )              // the "default" cache
app.use( boxExpressSession( { store: boxExpressCacheStore( "sessions" ) } ) )  // a named cache
```

The named cache has to already exist — `boxExpressCacheStore()` doesn't
register one, it just talks to it. If it doesn't, or the store later
fails, the app keeps serving: see **Falling back without durable
storage** below. A cache is registered in `boxlang.json`'s
top-level `caches` block, keyed by whatever name you pass in:

```json
{
	"datasources": {
		"sessionDB": {
			"driver": "mssql",
			"host": "${env.MSSQL_HOST:localhost}",
			"port": "${env.MSSQL_PORT:1433}",
			"database": "${env.MSSQL_DATABASE:myapp}",
			"username": "${env.MSSQL_USERNAME:sa}",
			"password": "${env.MSSQL_PASSWORD}"
		}
	},
	"caches": {
		"default": { "provider": "BoxCacheProvider" },
		"sessions": {
			"provider": "BoxCacheProvider",
			"properties": {
				"objectStore": "JDBCStore",
				"datasource": "sessionDB",
				"table": "boxlang_sessions",
				"autoCreate": false
			}
		}
	}
}
```

This makes sessions durable across restarts and shared across a cluster of
processes hitting the same database — exactly what the default `MemoryStore`
can't do. `JDBCStore` auto-detects the database vendor from the JDBC driver
(MySQL, Postgres, SQL Server, Oracle, SQLite, Derby, HSQLDB, MariaDB) to
generate the right `CREATE TABLE`/eviction SQL for each.

**Falling back without durable storage.** `boxExpressCacheStore()` (and
rate limiting's `cache` option) degrade instead of failing requests:

- The named cache isn't registered → sessions live in this process's
  memory, with a warning logged at startup.
- The cache is registered but in-memory (BoxLang's default
  `ConcurrentStore`, which `isDistributed()` reports as not shared) → it's
  used as-is, with a warning that entries aren't shared across instances
  or kept across restarts.
- A call to a durable cache fails at runtime (the database goes away) →
  that call uses per-process memory, logged at most once every 30 seconds,
  and the next call tries the cache again, so it recovers by itself.
  Entries written during the outage stay local; reads check the cache
  first, then local memory.

`getMode()` reports `"cache"` or `"local"` and `isDurable()` whether the
cache is backed by a durable, shared store. Options (also on
`boxExpressSession( { cache: "sessions" } )`, shorthand for
`{ store: boxExpressCacheStore( "sessions" ) }`): `fallback: false` makes a
missing cache throw at startup and runtime failures propagate, and
`requireDurable: true` refuses to start unless the cache is durable — use
either to fail fast in production instead of degrading.

Two separate things worth knowing, confirmed by running it against a real
database rather than assumed from the docs:

- Keep `"default"` in the `caches` block alongside any custom cache — don't
  replace the whole block with just your own entry. Overriding `caches`
  replaces it wholesale, and BoxLang's internal query engine relies on a
  `"default"` cache existing somewhere in it.
- **`autoCreate: true` is currently unreliable, and declaring `"default"`
  does *not* fix it.** It can fail at BoxLang startup with `Cache [default]
  does not exist`, because `JDBCStore`'s own auto-create check runs a query
  internally, and cache creation order isn't guaranteed to reach
  `"default"` first — this reproduced the same way whether `"default"` was
  declared or not, and regardless of where it sat in the JSON. The two
  bullets are unrelated fixes for unrelated problems. Safest path: create
  the table yourself once (a migration, or a one-time script) and leave
  `autoCreate: false`, as in the example above — that sidesteps the
  internal query entirely.
