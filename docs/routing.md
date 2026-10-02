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

Any `app.get(path, ...)` route automatically answers `HEAD` too — same
handler, same headers (including `Content-Length`), just with the body
thrown away before it reaches the client, same as Express. This applies to
static file serving as well. Register `app.head(path, ...)` explicitly only
when `HEAD` should behave differently than "run the `GET` handler and
discard the body" — e.g. to skip work the body needed but the headers
alone don't — and put it *before* the matching `app.get()` in registration
order, since the first matching layer in the stack wins.

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
