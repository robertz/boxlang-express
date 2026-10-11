# Middleware

Handlers are `(req, res, next) => {...}`. Call `next()` to continue to the
next matching layer, or `next(err)` to jump to error-handling middleware.
Error-handling middleware is any handler with **4** parameters —
`(err, req, res, next) => {...}` — detected the same way Express does
(`fn.length === 4`), and should be registered last.

`app.use(handler)` / `app.use(path, handler)` both also take more than one
handler (or a mix of handlers and a mountable `Router`) in a single call —
`app.use(mw1, mw2, mw3)` registers three separate stack layers at once,
running in the order given, same as `app.use(mw1); app.use(mw2);
app.use(mw3)` would:

```js
app.use(
	( req, res, next ) => { println( "#req.method# #req.path#" ); next() },
	( req, res, next ) => { res.set( "X-Powered-By", "BoxExpress" ); next() }
)
```

The first argument is only ever treated as a mount path when it's a plain
string — anything else (a closure, or a `Router`) is a target, so
`app.use(handler)` and `app.use(path, handler)` are told apart the same way
regardless of how many more arguments follow.

Built-in middleware factories (opt-in, same philosophy as `express.json()`),
each with its own global BIF mirroring the Express function of the same name:

```js
app.use( boxExpressJSON() )        // parses application/json bodies into req.body
app.use( boxExpressUrlencoded() )  // parses application/x-www-form-urlencoded bodies
app.use( "/public", boxExpressStatic( expandPath( "./public" ) ) )
app.use( boxExpressSession() )     // cookie-based sessions, req.session
app.use( boxExpressHelmet() )      // security headers — see helmet.md
app.use( boxExpressCors() )        // Cross-Origin Resource Sharing — see cors.md
app.use( boxExpressRateLimit() )   // 100 req/min per req.ip — see rate-limiting.md
```

Same thing spelled out via the underlying classes, if you'd rather not lean
on the BIFs:

```js
bodyParsers = new bxModules.boxexpress.models.middleware.BodyParsers()
app.use( bodyParsers.json() )
app.use( bodyParsers.urlencoded() )

staticFiles = new bxModules.boxexpress.models.middleware.StaticFiles()
app.use( "/public", staticFiles.serve( expandPath( "./public" ) ) )
```

`json()`/`boxExpressJSON()` and `urlencoded()`/`boxExpressUrlencoded()` cap
the request body at 100KB by default, to keep a slow or malicious client from
buffering an unbounded body into memory — override with `{ limit: bytes }`:

```js
app.use( boxExpressJSON( { limit: 5000000 } ) )  // 5MB
```

A body over the limit gets a `413` response and never reaches your route
handler. A JSON body that doesn't parse gets a `400`
(`{ error: true, message: "Malformed JSON body" }`), answered directly like
the `413` rather than passed to error-handling middleware.
`boxExpressStatic()`/`StaticFiles.serve()` resolve requested files
against the real (symlink-resolved) served directory, so a symlink placed
inside it can't be used to read files from outside it.

Static files ignore dotfiles by default: a path with any segment starting
with `.` (`/.env`, `/.git/config`) falls through to the next handler as if
the file weren't there, so a stray secret in the public directory isn't
served. `{ dotfiles: "deny" }` answers `403` instead, and
`{ dotfiles: "allow" }` serves them; `/.well-known/` is always served.
Files are streamed from disk rather than read into memory, so a large file
costs the same memory as a small one, `HEAD` never reads the file, and
files over 2 GB (including `Range` requests into them) work — the same
applies to `res.sendFile()` and `res.download()`.

## Built-in middleware

- [File uploads](uploads.md) — `boxExpressUpload()`
- [Sessions](sessions.md) — `boxExpressSession()`, `boxExpressCacheStore()`
- [Security headers](helmet.md) — `boxExpressHelmet()`
- [CORS](cors.md) — `boxExpressCors()`
- [Rate limiting](rate-limiting.md) — `boxExpressRateLimit()`
- [CSRF protection](csrf.md) — `boxExpressCsrf()`
- [Error handling](error-handling.md) — default `404`/`500` responses
