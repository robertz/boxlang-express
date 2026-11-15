# Router (mountable sub-app)

Same routing methods as `app`. Create one with `boxExpressRouter()` (or
`new bxModules.boxexpress.models.Router()` directly) and mount it with
`app.use("/api", apiRouter)` — request paths are matched relative to the
mount point, exactly like Express's `express.Router()`.

```js
apiRouter = boxExpressRouter()
apiRouter.get( "/ping", ( req, res ) => res.json( { pong: true } ) )
app.use( "/api", apiRouter )
```

Inside a mounted router, `req.path` is relative to the mount point and
`req.baseUrl` is the mount path (`"/api"` here; nested mounts add up) —
see [Request](request.md#more-request-properties).

Register all routes and middleware *before* calling `listen()`. The route
table isn't synchronized, so mutating it while requests are already being
served concurrently (each on its own virtual thread) isn't supported.

Path matching is case-insensitive for literal segments (BoxLang's `==`/`!=`
compare strings case-insensitively by default, same as classic CFML), which
happens to match Express's own default. A trailing `*` matches the rest of
the path (`/files/*`); a `*` anywhere else in the pattern throws at
registration time rather than silently matching more or less than you'd
expect. A route registered with no handler function also throws immediately,
rather than silently registering nothing.

A trailing `:name?` marks the last param as optional — `/users/:id?` matches
both `/users/42` (`req.params.id` is `"42"`) and `/users` (`req.params.id`
isn't set at all, so read it with `req.params.id ?: someDefault`). Same
restriction as `*`: an optional param is only supported as the *final* path
segment — `/a/:id?/b` throws at registration time rather than silently doing
something you didn't ask for, since matching an optional param anywhere else
would need real backtracking this router doesn't implement.

A trailing `*name` is a wildcard that also captures what it matched:
`/assets/*rest` gives `req.params.rest` `"css/site.css"` for
`/assets/css/site.css` (and `""` for `/assets`). For params that must
fit a pattern, or share a segment with other text, see [Path
patterns](#path-patterns) below.

Any `app.get(path, ...)` route automatically answers `HEAD` too — same
handler, same headers (including `Content-Length`), just with the body
thrown away before it reaches the client, same as Express. This applies to
static file serving as well. Register `app.head(path, ...)` explicitly only
when `HEAD` should behave differently than "run the `GET` handler and
discard the body" — e.g. to skip work the body needed but the headers
alone don't — and put it *before* the matching `app.get()` in registration
order, since the first matching layer in the stack wins.

## Path patterns

A param can be limited to values matching a regular expression, written
in parentheses after its name. A request that doesn't fit simply doesn't
match that route, so a more general one can follow it:

```js
app.get( "/users/:id(\d+)", showUserById )     // /users/42
app.get( "/users/:name", showUserByName )       // /users/ada
```

That replaces the usual `if ( !isNumeric( req.params.id ) ) return
next()` at the top of a handler. The pattern has to match the whole
segment (`42abc` doesn't match `\d+`), is case-insensitive like the rest
of the path, and can't contain a `/`.

A segment can also mix params with literal text, or hold more than one:

| Pattern | Matches | `req.params` |
|---|---|---|
| `/flights/:from-:to` | `/flights/LAX-JFK` | `{ from: "LAX", to: "JFK" }` |
| `/api/v:version/ping` | `/api/v2/ping` | `{ version: "2" }` |
| `/files/:name(.+).json` | `/files/report.json` | `{ name: "report" }` |
| `/dates/:y(\d{4})-:m(\d\d)-:d(\d\d)` | `/dates/2026-11-15` | `{ y: "2026", m: "11", d: "15" }` |
| `/pages/:n(\d+)?` | `/pages` and `/pages/3` | `{}` or `{ n: "3" }` |
| `/assets/*rest` | `/assets/css/site.css` | `{ rest: "css/site.css" }` |

The rules:

- In a segment that has a constraint, more than one param, or text
  around a param, a param's name is letters, digits and underscores and
  ends at the first other character — that's what makes `:from-:to` two
  params.
- **A segment that is only `:name` is unchanged**: everything after the
  colon is the name, so an existing `/:user-id` route still captures one
  param called `user-id`. To put a literal suffix on a single param, give
  it a constraint: `/:name(.+).json`, not `/:name.json`.
- A param never matches an empty string.
- Literal text is matched literally — the `.` in `.json` is a dot, not
  "any character".
- `?` (optional) works on a constrained param when it's the whole final
  segment, same as `:name?`.
- A pattern that can't be parsed — an unclosed `(`, the same name twice
  in a segment, an invalid regex — throws `BoxExpress.InvalidRoutePath`
  when the route is registered, not on the first request.

They work anywhere a path does: `app.use( "/t/:tenant([a-z]{3})", router )`,
`router.route(...)`, and `app.param()` callbacks run for these params as
for any other. Still not supported: a wildcard or optional param anywhere
but the final segment, and a pattern spanning more than one segment.

A constraint is a regular expression you write, run against a path a
client chooses, so the usual care applies: avoid nested quantifiers like
`(a+)+`, which can take exponential time on a crafted input.

## Route params (`app.param(name, callback)`)

`param(name, (req, res, next, value) => {...})` runs once for any matched
route whose path pattern captures that param name, before the route's own
handler(s) run — same idea as Express's `app.param()`. Handy for centralizing
a lookup/validation step (e.g. loading a record by `:id`) instead of
repeating it in every handler that has that param:

```js
app.param( "id", ( req, res, next, value ) => {
	req.params.id = value.trim()   // normalize, validate, load from a DB, etc.
	next()                          // or next(err) to jump to error-handling middleware
} )

app.get( "/users/:id", ( req, res ) => {
	res.json( { id: req.params.id } )   // already normalized
} )
```

Works the same on a standalone `Router` — `router.param(...)`.

## Chainable routes (`app.route(path)`)

`route(path)` returns a small builder scoped to one path, so multiple
methods on the same path don't need to repeat it — sugar over calling
`app.get/post/put/...` individually, nothing more:

```js
app.route( "/widgets" )
	.get( ( req, res ) => res.json( { op: "list" } ) )
	.post( ( req, res ) => res.json( { op: "create" } ) )
```

Works the same on a standalone `Router` — `router.route(path)`.
