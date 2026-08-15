# BoxExpress

An Express.js-style web framework for BoxLang. Runs as a standalone HTTP server
(backed by the JDK's built-in `com.sun.net.httpserver.HttpServer`, with a
virtual thread per request) — no servlet container required.

BoxExpress is a BoxLang module (`ModuleConfig.bx` at the project root, `type:
"boxlang-modules"` in `box.json`). Installed into a project's
`boxlang_modules/boxexpress/` (or the runtime-wide `~/.boxlang/modules/`),
BoxLang registers a `bxModules.boxexpress` mapping automatically, and a global
`boxExpress()` factory function — the closest match to Node's
`const app = require('express')()`:

```js
app = boxExpress()

app.get( "/", ( req, res ) => {
	res.send( "Hello World" )
} )

app.listen( 3000, ( port ) => {
	println( "listening on #port#" )
} )
```

`boxExpress()` is a custom BIF (`bifs/boxExpress.bx`), auto-registered by the
module system the moment BoxExpress loads — no `new` or namespace needed. It's
just a thin wrapper: `new bxModules.boxexpress.models.BoxExpress()` still
works identically if you'd rather be explicit about where it comes from. Every
other model gets the same treatment, each its own thin BIF wrapper: the
built-in middleware factories — `boxExpressJSON()`, `boxExpressUrlencoded()`,
`boxExpressStatic()`, `boxExpressUpload()`, `boxExpressSession()`,
`boxExpressHelmet()`, `boxExpressCors()`, `boxExpressRateLimit()`,
`boxExpressCsrf()` (see [Middleware](#middleware) below) — and
`boxExpressRouter()`, wrapping `new
bxModules.boxexpress.models.Router()` and mirroring Express's own
`express.Router()`.

## Documentation

This README covers the API reference. For the full documentation —
getting started, configuration, routing, middleware, request/response,
views, sessions, static files & uploads, error handling, and process
lifecycle, each with runnable examples — see
[robertz/express-test](https://github.com/robertz/express-test), a working
demo app that doubles as BoxExpress's own docs site: every page you can
read about, you can also click through and try live (`boxlang app.bxs`,
then browse to `/docs`).

## Install

Published on [ForgeBox](https://www.forgebox.io/) as `boxlang-express`:

```bash
box install boxlang-express
```

If you would like to install the module locally, use the `--local` flag
during installation.

```bash
box install boxlang-express --local
```

That drops it into a project's local `boxlang_modules/boxexpress/`. Either
way, the mapping (`bxModules.boxexpress`) is registered by the BoxLang
runtime the moment it discovers the module — but for a local install, that
discovery is relative to the **process's current working directory** (no
upward search), so `boxlang` has to be invoked from the directory
containing `boxlang_modules/`, same as `node_modules` resolution rules of
thumb in npm-land.

**Gotcha:** if a module of the same name exists in *both* places — a
project-local `boxlang_modules/boxexpress/` and a global
`~/.boxlang/modules/boxexpress/` — the global copy wins, even though
BoxLang's own `modulesDirectory` config lists the project-local path first.
If a local change doesn't seem to take effect, check whether a global
install is shadowing it.

To develop against a local checkout instead (e.g. to test unreleased
changes), symlink it in rather than installing from ForgeBox:

```bash
mkdir -p boxlang_modules
ln -s /path/to/boxlang-express boxlang_modules/boxexpress
```

## Hacking on BoxExpress itself

Requires BoxLang (tested against 1.15.0) on the JVM. No external dependencies
beyond TestBox for the test suite (`box install`).

```bash
boxlang examples/server.bxs
```

Then, from another shell:

```bash
curl localhost:3000/
curl localhost:3000/users/42
curl "localhost:3000/search?q=cats"
curl -X POST -H "Content-Type: application/json" -d '{"a":1}' localhost:3000/echo
curl localhost:3000/api/ping
curl localhost:3000/api/items/7
curl -i localhost:3000/public/hello.txt
curl localhost:3000/does-not-exist
curl localhost:3000/boom
curl -i localhost:3000/set-cookie
curl -i localhost:3000/go-home
curl localhost:3000/slow    # run a few of these in parallel to see concurrent handling
curl localhost:3000/greet/Ada
curl localhost:3000/greet-hbs/Ada
```

Inside this repo, `examples/` and `tests/` reference the library via relative
paths (`new "../models/BoxExpress"()`) rather than the `bxModules.boxexpress`
mapping — that's deliberate: relative-path `new` resolves against the calling
file's own location and works regardless of where `boxlang` is invoked from,
which matters for a repo whose own scripts live in subdirectories
(`examples/`, `tests/specs/`) rather than at the module root.

## API

### App

- `app.get/post/put/patch/delete/head/all(path, ...handlers)`
- `app.use(handler)` / `app.use(path, handler)` / `app.use(path, router)`
- `app.param(name, callback)` — see [Route params](#route-params-appparamname-callback) below
- `app.route(path)` — see [Chainable routes](#chainable-routes-approutepath) below
- `app.set(name, value)` / `app.getSetting(name)`
- `app.locals` — a plain struct merged into every `res.render()` call's data;
  see [Views](#views-resrender) below
- `app.listen(port, callback, options)` — starts the server and blocks the calling thread by default
- `app.close()` — stops the server, and breaks a blocked `listen()` (from any thread)

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

`options.backlog` sets the underlying `HttpServer`'s TCP accept queue depth
(default `1024`) — how many pending connections the OS will hold before
refusing new ones outright, independent of how fast requests are actually
being handled. Confirmed with a real load test, not assumed: the JDK's own
default (`0`) started refusing connections with a reset once concurrency
passed roughly 65-70 in repeated runs, even though request handling itself
stayed fast the whole time; raising it to `1024` pushed that past 150 with
no other change. Rarely needs touching — a reverse proxy in front (already
required, since BoxExpress's `HttpServer` never terminates TLS itself) will
usually queue connections before this limit is ever reached — but it's
there for a direct-exposure deployment or a deliberately higher ceiling.

Every request gets a line on stdout as soon as it's received —
`[2026-08-10 10:45:41] GET /users/42 127.0.0.1` — there's no setting to turn
this off yet.

#### Auto-restart on file change (`app.set("reloadOnChange", true)`)

```js
app.set( "reloadOnChange", true )
```

Dev-only convenience — watches the current working directory (recursively,
skipping `boxlang_modules/`, `node_modules/`, `.git`, and other dot-dirs) for
`.bx`/`.bxs`/`.bxm` changes and restarts the process on save. Registration is
a one-time recursive snapshot taken at startup — a directory created *after*
the server starts won't be picked up until the next restart.

There's no hot reload inside a running JVM once BoxLang has compiled your
classes, so a restart replaces the whole process: launch a replacement by
replaying the exact original JVM invocation (via `ProcessHandle.current()`,
so it works whatever the entry script is named or however it was launched),
then close the current server and exit — same net effect as running under
`entr -r` or `nodemon`, just without the extra dependency. Launching the
replacement is attempted *before* closing the current server, not after —
if it fails for any reason (a bad reconstructed command, a transient OS
resource limit), that's caught, logged, and the current server is left
running rather than dying with nothing to replace it.

Separate from `app.set("env", "development")` — use either independently.

### Router (mountable sub-app)

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

#### Route params (`app.param(name, callback)`)

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

#### Chainable routes (`app.route(path)`)

`route(path)` returns a small builder scoped to one path, so multiple
methods on the same path don't need to repeat it — sugar over calling
`app.get/post/put/...` individually, nothing more:

```js
app.route( "/widgets" )
	.get( ( req, res ) => res.json( { op: "list" } ) )
	.post( ( req, res ) => res.json( { op: "create" } ) )
```

Works the same on a standalone `Router` — `router.route(path)`.

### Request

`req.method`, `req.path`, `req.originalUrl`, `req.query`, `req.params`,
`req.headers`, `req.get(name)`, `req.cookies`, `req.ip`, `req.protocol`,
`req.secure`, `req.hostname`, `req.body` (populated by body-parsing
middleware, empty struct otherwise), `req.rawExchange()` (an escape hatch to
the underlying `com.sun.net.httpserver.HttpExchange`, for anything not
covered by the rest of the API).

`req.ip` is always the direct TCP peer by default — safe, since a client
can't spoof it, but wrong behind a reverse proxy (it'll report the proxy's
IP). Opt in to trusting `X-Forwarded-For` with `app.set("trust proxy", true)`,
same as Express; leave it off (the default) unless you actually control the
proxy in front of this, since with it on, anyone who can reach the app
directly can forge their reported IP.

`req.protocol`/`req.secure`/`req.hostname` follow the same trust model.
BoxExpress's own `HttpServer` never terminates TLS itself, so `req.protocol`
is always `"http"` (`req.secure` always `false`) unless `trust proxy` is on
*and* the request carries `X-Forwarded-Proto: https` — the shape you'd see
behind a TLS-terminating reverse proxy. `req.hostname` is the `Host` header
(or, with `trust proxy` on, `X-Forwarded-Host` if present) with any `:port`
suffix stripped — an IPv6 host (`[::1]:3000`) is left bracketed rather than
mangled at the first colon.

### Response

`res.status(code)`, `res.set(name,val)` / `res.header(...)`, `res.type(mime)`,
`res.send(data)`, `res.json(data)`, `res.sendStatus(code)`,
`res.redirect(url, code=302)`, `res.cookie(name,val,options)`,
`res.sendBytes(bytes, contentType)`, `res.sendFile(path, options)`,
`res.download(path, filename)`, `res.end(data)`, `res.render(view, data)`,
`res.dump(data)` (sends BoxLang's rich, collapsible `dump()` HTML view of a
variable as the response — a quick debugging escape hatch; see the note
below).

Calling a second terminal method (`send`/`json`/`redirect`/`end`/`sendBytes`/
`sendFile`/`download`/`render`/`dump`) on the same response throws — mirrors
Express's "headers already sent".

`res.dump(data)` only forwards `data` to BoxLang's `dump()` BIF — none of
its other options (`label`, `expand`, `top`, etc.). It round-trips the HTML
through a temp file, since `dump(format="html")` only ever writes to the
process console under BoxExpress's CLI-based `HttpServer`; that round trip,
plus the dump renderer itself, cost more than `res.json()`, so it's meant
for an occasional debug route, not a hot path. Reach for the same temp-file
pattern directly (see [Response.bx](models/Response.bx)) if you need those
extra options.

`res.sendFile(path, options)` streams a file from disk, guessing its
`Content-Type` from the extension (override with `options.contentType`).
`res.download(path, filename)` is the same thing but always sets
`Content-Disposition: attachment`, so the browser saves it instead of
rendering it inline — `filename` overrides the name it's saved as (defaults
to the source file's own name):

```js
app.get( "/report", ( req, res ) => {
	res.sendFile( expandPath( "./reports/latest.pdf" ) )     // renders inline
} )

app.get( "/report/download", ( req, res ) => {
	res.download( expandPath( "./reports/latest.pdf" ), "report.pdf" )
} )
```

If the path is built from user input, pass `options.root` — `sendFile()`
then resolves the path against that root and rejects (throws) anything that
resolves outside it, the same real-path containment check `StaticFiles` and
`render()` use:

```js
app.get( "/files/:name", ( req, res ) => {
	res.sendFile( req.params.name, { root: expandPath( "./public" ) } )
} )
```

Both `sendFile()` and static file serving (`StaticFiles`/`boxExpressStatic()`)
set `ETag` and `Last-Modified` on every response, and answer a conditional
request (`If-None-Match` or, failing that, `If-Modified-Since`) with an empty
`304 Not Modified` instead of re-sending the file — same behavior as
`express.static()`/`res.sendFile()`, so a browser's normal caching just
works. The `ETag` is a cheap weak tag (`W/"<size>-<mtime>"`, no file hash),
so it changes whenever the file's size or modified time changes on disk.

Both also accept `options.maxAge` (seconds) to set `Cache-Control:
public, max-age=<n>`, letting a browser skip revalidation entirely for that
long instead of asking on every request — off by default (no header at
all) since caching a response the app didn't ask to be cached is the wrong
default:

```js
app.use( "/public", boxExpressStatic( expandPath( "./public" ), { maxAge: 86400 } ) )  // 1 day

app.get( "/report", ( req, res ) => {
	res.sendFile( expandPath( "./reports/latest.pdf" ), { maxAge: 3600 } )  // 1 hour
} )
```

#### Range requests (partial content)

Both `sendFile()`/`download()` and static file serving honor a `Range`
request header (RFC 7233) and respond `206 Partial Content` with just the
requested slice — what makes video/audio scrubbing and resumable downloads
work, since the client doesn't have to (re-)download the whole file to seek
or resume. Every file response sets `Accept-Ranges: bytes`, even a full
`200`, so a client knows it can send a `Range` request on a later one:

```js
app.get( "/video", ( req, res ) => {
	res.sendFile( expandPath( "./media/clip.mp4" ) )
} )
// curl -H "Range: bytes=0-1023" localhost:3000/video
// -> 206, Content-Range: bytes 0-1023/<total>, body is just those 1024 bytes
```

A range naming a start beyond the file's size gets a `416 Range Not
Satisfiable` with `Content-Range: bytes */<total>` and no body, matching the
spec. Two things are deliberately out of scope, the same trade-off most
minimal static-file servers make: a request naming more than one range
(`bytes=0-10,20-30`) falls back to a full `200` response rather than a
`multipart/byteranges` reply, and `If-Range` (a conditional range against a
validator) isn't supported — a `Range` request is always attempted
regardless of freshness. See [RangeParser.bx](models/RangeParser.bx).

### Views (`res.render`)

Renders a view from a configured views directory and sends the result as
`text/html`. Two engines, picked by the view file's extension:

```js
app.set( "views", expandPath( "./views" ) )

app.get( "/greet/:name", ( req, res ) => {
	res.render( "greeting", { name: req.params.name, age: 30 } )        // -> views/greeting.bxm
} )

app.get( "/greet-hbs/:name", ( req, res ) => {
	res.render( "greeting.hbs", { data: { name: req.params.name } } )   // -> views/greeting.hbs
} )
```

**`.bxm`** — BoxLang's native server-page format (think `.cfm`), run via
`include` + `savecontent`. `views/greeting.bxm`:

```html
<bx:output>
<h1>Hello, #data.name#!</h1>
<p>You are #data.age# years old.</p>
</bx:output>
```

`data` is whatever struct you pass as `render()`'s second argument —
reference its keys as `data.whatever`. `#var#` interpolation only happens
inside a `<bx:output>` block, exactly like `<cfoutput>` in classic CFML —
plain text outside one is left as literal `#...#`, unevaluated.

**`.hbs`** — [Handlebars](https://handlebarsjs.com/) via the bundled
[handlebars.java](https://github.com/jknack/handlebars.java) (`libs/`, ~1MB —
vendored in this repo, nothing extra to install). `views/greeting.hbs`:

```handlebars
<h1>Hello, {{data.name}}!</h1>
<p>Things: {{#each data.things}}{{this}}{{#unless @last}}, {{/unless}}{{/each}}</p>
```

Here `data` is whatever you passed to `render()`, but as the Handlebars
*render context* rather than a magic variable — `{{data.name}}` only works
because the struct you passed has a top-level `data` key (see the route
above); pass your view struct directly and reference `{{name}}` instead if
you'd rather skip that nesting.

A view with no extension gets `.bxm` appended by default — change that with
`app.set("view engine", "hbs")` to make `.hbs` the default instead, same
idea as Express's view-engine setting.

`render()` throws if `app.set("views", ...)` was never called. `view` is
resolved and checked against the views directory's real (symlink-resolved)
path before either engine touches the file — a request for
`res.render(req.query.tpl)` with `tpl=../../etc/passwd` throws instead of
rendering whatever that resolves to, but treat any user input reaching
`render()`'s first argument as something to validate yourself regardless;
this just stops the obvious traversal case.

#### `app.locals` / `res.locals`

Two plain structs merged into every `render()` call's data, so values a
route doesn't set explicitly — a site name, the current user, a nonce —
don't have to be threaded through every single `res.render()` call by hand.
`app.locals` is set once, application-wide; `res.locals` is set per request
(typically from middleware) and only lives for that one request/response
cycle. Precedence, low to high: `app.locals`, then `res.locals` (overriding
`app.locals`), then whatever you pass as `render()`'s own `data` argument
(overriding both) — same order Express uses:

```js
app.locals.siteName = "My Site"

app.use( ( req, res, next ) => {
	res.locals.user = req.session.user ?: "guest"
	next()
} )

app.get( "/", ( req, res ) => {
	res.render( "home", {} )   // views/home.bxm sees data.siteName and data.user
} )
```

### Middleware

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
app.use( boxExpressHelmet() )      // security headers — see below
app.use( boxExpressCors() )        // Cross-Origin Resource Sharing — see below
app.use( boxExpressRateLimit() )   // 100 req/min per req.ip — see below
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
handler. `boxExpressStatic()`/`StaticFiles.serve()` resolve requested files
against the real (symlink-resolved) served directory, so a symlink placed
inside it can't be used to read files from outside it.

#### File uploads (`boxExpressUpload()` / `Multipart`)

Opt-in `multipart/form-data` parsing, mirroring [multer](https://github.com/expressjs/multer)'s
basic usage:

```js
app.use( boxExpressUpload() )                                       // in-memory only
app.use( boxExpressUpload( { dest: expandPath( "./uploads" ) } ) )  // also saved to disk
```

or via the underlying class:

```js
multipart = new bxModules.boxexpress.models.middleware.Multipart()
app.use( multipart.upload( { dest: expandPath( "./uploads" ) } ) )
```

Non-file fields land in `req.body`, same as the other body parsers. File
fields land in `req.files`, a struct keyed by field name, each value an
**array** of file structs (a field can carry more than one file):

```js
app.post( "/upload", boxExpressUpload( { dest: expandPath( "./uploads" ) } ), ( req, res ) => {
	res.json( { note: req.body.note, avatar: req.files.avatar[ 1 ] } )
	// avatar: { fieldName, filename, contentType, size, buffer, path }
} )
```

Every file struct always has `fieldName`, `filename` (the name the client
sent — never trust it as a disk path), `contentType`, `size`, and `buffer`
(the raw bytes, in memory). `path` is only present when `dest` was given —
the file is saved there under a generated UUID name, never the client's own
filename, so there's nothing for a malicious filename to path-traverse or
collide with. Like the other parsers, the whole body is capped at 10MB by
default — override with `{ limit: bytes }`; an oversized upload gets a `413`
before your handler runs.

#### Sessions (`boxExpressSession()` / `Session`)

Cookie-based sessions, mirroring [express-session](https://github.com/expressjs/session)'s
default (in-memory) behavior. The cookie carries only an opaque, unguessable
session ID — the actual data lives server-side, keyed by that ID — and is
rolling: every request through this middleware resets both the cookie's and
the stored data's expiry to `maxAge` from now.

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
`req.sessionID` is the current session's ID. Call `req.destroySession()` to
log a user out — it removes the server-side data and expires the cookie.

The default store is in-memory on the `Session` instance (so it doesn't
survive a restart and isn't shared across processes) — swap in something
durable by passing `{ store: myStore }`, an object exposing
`get(id)` / `set(id, data, maxAge)` / `destroy(id)`.

##### Durable sessions (`boxExpressCacheStore()` / `CacheStore`)

A ready-made `store` backed by BoxLang's own [cache service](https://boxlang.ortusbooks.com/boxlang-language/cache)
(the `cache()` BIF) — memory by default, or a real SQL table if the named
cache is configured with `objectStore: "JDBCStore"`:

```js
app.use( boxExpressSession( { store: boxExpressCacheStore() } ) )              // the "default" cache
app.use( boxExpressSession( { store: boxExpressCacheStore( "sessions" ) } ) )  // a named cache
```

The named cache has to already exist — `boxExpressCacheStore()` doesn't
register one, it just talks to it. A cache is registered in `boxlang.json`'s
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

#### Security headers (`boxExpressHelmet()` / `Helmet`)

Applies a set of response headers that harden common attack surfaces —
clickjacking, MIME-sniffing, referrer leakage, cross-origin reads —
mirroring the npm [`helmet`](https://github.com/helmetjs/helmet) package's
most commonly used defaults, with no configuration needed:

```js
app.use( boxExpressHelmet() )
```

| Header | Default |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `SAMEORIGIN` |
| `X-DNS-Prefetch-Control` | `off` |
| `Referrer-Policy` | `no-referrer` |
| `X-Permitted-Cross-Domain-Policies` | `none` |
| `Cross-Origin-Opener-Policy` | `same-origin` |
| `Cross-Origin-Resource-Policy` | `same-origin` |
| `Strict-Transport-Security` | *(off by default — opt in)* |
| `Content-Security-Policy` | *(off by default — opt in)* |

Every option takes three shapes: omitted (the default above), `false`
(skip that header entirely), or an exact string to use instead:

```js
app.use( boxExpressHelmet( {
	frameOptions: "DENY",              // override the default value
	referrerPolicy: false,             // skip this header entirely
	hsts: true,                        // opt in, using the built-in default value
	contentSecurityPolicy: "default-src 'self'"   // opt in, with your own policy
} ) )
```

`hsts` and `contentSecurityPolicy` are opt-in, not on by default like the
rest: `Strict-Transport-Security` only makes sense over an actually-secure
connection — BoxExpress's own `HttpServer` never terminates TLS itself (see
`req.secure` above) — so turning it on unconditionally could advertise a
guarantee the app doesn't meet. A generic default `Content-Security-Policy`
is exactly the kind of thing that breaks a real app's own inline
scripts/styles or asset domains if applied blindly, so it needs the app's
own policy string rather than a one-size-fits-all default.

BoxExpress never sets an `X-Powered-By` header in the first place (unlike
Express), so there's nothing here to remove the way `helmet` does.

#### CORS (`boxExpressCors()` / `Cors`)

Cross-Origin Resource Sharing, mirroring the npm [`cors`](https://github.com/expressjs/cors)
package's most commonly used options. With no options, reflects whatever
`Origin` the request sent (or `*` if there wasn't one) — permissive by
default, same as the npm package:

```js
app.use( boxExpressCors() )
app.use( boxExpressCors( { origin: "https://example.com" } ) )
app.use( boxExpressCors( { origin: [ "https://a.com", "https://b.com" ], credentials: true } ) )
```

| Option | Default | Effect |
|---|---|---|
| `origin` | `true` | `true` reflects the request's `Origin`; `false` disables CORS entirely; a string allows only that exact origin (or `"*"` verbatim); an array allows any origin in the list |
| `methods` | `GET,HEAD,PUT,PATCH,POST,DELETE` | `Access-Control-Allow-Methods` on a preflight response |
| `allowedHeaders` | *(reflects the preflight's own request)* | `Access-Control-Allow-Headers` on a preflight response |
| `exposedHeaders` | *(none)* | `Access-Control-Expose-Headers` on every response |
| `credentials` | `false` | sets `Access-Control-Allow-Credentials: true` when `true` |
| `maxAge` | *(none)* | `Access-Control-Max-Age` (seconds) on a preflight response |
| `preflightContinue` | `false` | call `next()` for a preflight instead of answering it directly |
| `optionsSuccessStatus` | `204` | status code for a handled preflight |

A CORS preflight — an `OPTIONS` request carrying
`Access-Control-Request-Method` — is answered directly by this middleware
(status `204`, the relevant headers, no body) rather than falling through
to the router, since nothing would otherwise be registered to handle
`OPTIONS` on an arbitrary route. Pass `{ preflightContinue: true }` if a
later handler needs to see the preflight request itself instead.

#### Rate limiting (`boxExpressRateLimit()` / `RateLimit`)

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
reverse proxy without `app.set("trust proxy", true)`, every request shares
the proxy's own IP as the key, rate-limiting the whole app together rather
than per client. The store is in-memory on each `RateLimit` instance (same
trade-off as `Session`'s default `MemoryStore` — fine for a single-process
app, not a cluster).

#### CSRF protection (`boxExpressCsrf()` / `Csrf`)

Mirrors the classic [`csurf`](https://github.com/expressjs/csurf) package's
session-based token strategy. The token lives in `req.session`, not its own
cookie, so this must be registered *after* `boxExpressSession()`:

```js
app.use( boxExpressSession() )
app.use( boxExpressUrlencoded() )   // before Csrf if reading the token from a form field
app.use( boxExpressCsrf() )

app.get( "/form", ( req, res ) => {
	res.render( "form", { csrfToken: req.csrfToken() } )
} )
```

```html
<!-- views/form.bxm -->
<form method="POST" action="/form">
	<input type="hidden" name="_csrf" value="#data.csrfToken#">
	...
</form>
```

```js
app.post( "/form", ( req, res ) => {
	// A missing/mismatched token never reaches this handler — it gets a
	// 403 from the middleware first.
	res.send( "ok" )
} )
```

"Safe" methods (`GET`/`HEAD`/`OPTIONS` by default, override with
`options.ignoreMethods`) never validate — a token is only minted and
exposed via `req.csrfToken()` for those, since that's how a token gets into
a form before any state-changing request happens. Every other method must
submit a matching token: read from `req.body[fieldName]` first
(`options.fieldName`, default `"_csrf"`), falling back to a request header
(`options.headerName`, default `"X-CSRF-Token"`) for non-form (JSON/AJAX)
clients that can't rely on a hidden form field. Calling
`boxExpressCsrf()` before `req.session` exists (i.e. before
`boxExpressSession()`) throws immediately rather than silently doing
nothing — a misconfiguration here should be loud, not a security hole that
only shows up in production.

If no route matches, a default `404` JSON response is sent. If a handler
throws (or calls `next(err)`) and no error-handling middleware is registered,
a default `500` JSON response is sent — with a generic `"Internal Server
Error"` message, not the real exception message, since that can otherwise
leak internal details (file paths, driver errors, etc.) to an unauthenticated
client. The real message is still logged to stdout either way. Opt in to
seeing it in the response too — for local development, never in
production — with:

```js
app.set( "env", "development" )
```

## Tests

[TestBox](https://testbox.ortusbooks.com/) specs, run headlessly (no server
needed — this runs the same way `examples/server.bxs` does, just against
TestBox instead of an HTTP request):

```bash
boxlang setup-tests.bxs   # once per checkout
boxlang run-tests.bxs     # every time after that
```

`run-tests.bxs` exits non-zero on any failure/error, so it's CI-friendly —
just run `setup-tests.bxs` once beforehand (e.g. in your CI image build step
or a `pretest` script). Structure:

- `tests/specs/RouterSpec.bx` — unit tests for path matching and the
  middleware/`next()` chain, using lightweight fake `req`/`res` structs
  instead of a real `HttpExchange` (Router never touches anything else).
- `tests/specs/BoxExpressIntegrationSpec.bx` — spins up a real app on
  `localhost:4321` in `beforeAll()` and hits it with real HTTP requests
  (`tests/helpers/HttpClient.bx`, a thin `HttpURLConnection` wrapper),
  exercising `Request`/`Response`/`BodyParsers`/`StaticFiles`/`render()`
  together, plus a concurrency check using `bx:thread`.
- `tests/specs/BifsSpec.bx` — the same idea, but built entirely through the
  global BIFs (`boxExpress()`, `boxExpressJSON()`, etc.) on `localhost:4322`,
  to make sure the friendly entry points actually work, not just the
  underlying classes.
- `tests/fixtures/` — a static file and a `.bxm` view the integration specs
  serve/render.

`setup-tests.bxs` creates a self-referencing `boxlang_modules/boxexpress`
symlink (gitignored, not committed) so BoxLang discovers this project as a
real module and registers the BIFs `BifsSpec` needs — module discovery only
happens once at BoxLang process startup, so this has to run as its own
invocation before `run-tests.bxs`, not get folded into it.

Two more BoxLang-specific things that shaped how these are written:

- Bare (non-`var`) assignment inside a `describe`/`it`/`beforeEach` closure
  doesn't reliably cross into sibling closures — `RouterSpec` builds its own
  `var router` per test instead of sharing one via `beforeEach`.
  `variables.xxx` set in `beforeAll()`/`afterAll()` *does* cross into `it()`
  blocks, since those are real class methods rather than closure arguments —
  that's what `BoxExpressIntegrationSpec` uses to share the running app.
- `expandPath()` resolves relative to the top-level entry script
  (`run-tests.bxs`, at the project root) — not relative to whichever `.bx`
  file happens to call it — hence the fixture paths in
  `BoxExpressIntegrationSpec` are project-root-relative (`tests/fixtures/...`)
  rather than `../fixtures/...`.

## Changelog

**0.1.15**
- **Fix:** `listen()` created its underlying `HttpServer` with a TCP accept
  backlog of `0` (the JDK default), which turned out to be a real capacity
  ceiling — confirmed with a live load test (`ab`): request handling itself
  stayed fast and error-free throughout, but new connections started getting
  refused with a reset once concurrency passed roughly 65-70 in repeated
  runs. `listen(port, callback, { backlog: n })` now defaults to `1024`,
  which pushed the same test past 150 with no other change. See
  [BoxExpress.bx](models/BoxExpress.bx).

**0.1.14**
- **Fix + feature:** `app.get(path, ...)` routes now automatically answer
  `HEAD` too — same handler and headers (including `Content-Length`), body
  discarded, same as Express — instead of 404ing. This also fixes a real bug
  in static file serving: it already accepted `HEAD` requests, but sent the
  full file body anyway, a spec violation for a `HEAD` response. Register
  `app.head(path, ...)` explicitly (before the matching `app.get()`) only
  when `HEAD` should behave differently than discarding the `GET` body.
- **Fix:** `app.use()`/`router.use()` only ever accepted a single handler
  (or a single path + single handler), unlike route registration
  (`app.get(path, mw1, mw2, handler)`), which already took a variadic
  chain. Calling `app.use(mw1, mw2, mw3)` — a common Express pattern —
  didn't just silently drop the extra arguments, it threw a confusing
  low-level error (the second handler got mistaken for a mount path).
  `use()` now accepts any number of handlers (and/or a `Router`) in one
  call, at the same mount point, registered in order. See
  [Router.bx](models/Router.bx).
- Added `boxExpressHelmet()` — security-headers middleware mirroring the
  npm [`helmet`](https://github.com/helmetjs/helmet) package's most
  commonly used defaults (`X-Frame-Options`, `X-Content-Type-Options`,
  `Referrer-Policy`, etc.), each individually overridable or disable-able.
  `Strict-Transport-Security` and `Content-Security-Policy` are opt-in
  rather than on by default. See [Helmet.bx](models/middleware/Helmet.bx).
- Added `boxExpressCors()` — Cross-Origin Resource Sharing middleware
  mirroring the npm [`cors`](https://github.com/expressjs/cors) package's
  common options (`origin`, `methods`, `allowedHeaders`, `credentials`,
  `maxAge`). Answers a CORS preflight `OPTIONS` request directly, since
  nothing would otherwise be registered to handle it. See
  [Cors.bx](models/middleware/Cors.bx).
- Added `boxExpressRateLimit()` — fixed-window rate limiting mirroring the
  npm [`express-rate-limit`](https://github.com/express-rate-limit/express-rate-limit)
  package's common options (`windowMs`, `max`, `keyGenerator`), keyed by
  `req.ip` by default. Sets the draft-standard `RateLimit-*` headers and
  responds `429` with `Retry-After` once a key's count exceeds `max`. See
  [RateLimit.bx](models/middleware/RateLimit.bx).
- Added `boxExpressCsrf()` — CSRF protection mirroring the classic
  [`csurf`](https://github.com/expressjs/csurf) package's session-based
  token strategy. Requires `req.session` (register after
  `boxExpressSession()`, which it throws immediately about if missing);
  exposes `req.csrfToken()`, validates `req.body._csrf` (or a request
  header) on every method other than `GET`/`HEAD`/`OPTIONS`. See
  [Csrf.bx](models/middleware/Csrf.bx).
- `boxExpressStatic()`/`res.sendFile()` now accept `options.maxAge`
  (seconds) to set `Cache-Control: public, max-age=<n>` alongside the
  `ETag`/`Last-Modified` they already set — off by default, no header at
  all unless asked for.
- Added `boxExpressCacheStore()` — a `Session` `store` backed by BoxLang's
  own `cache()` service, so `{ store: boxExpressCacheStore( "sessions" ) }`
  makes sessions durable across restarts and shared across a cluster when
  the named cache is configured with `objectStore: "JDBCStore"` (a real SQL
  table) instead of the in-memory default. See
  [CacheStore.bx](models/stores/CacheStore.bx).

**0.1.13**
- Added `Range` request support (RFC 7233) to `res.sendFile()`/`download()`
  and static file serving — a request naming a byte range now gets a `206
  Partial Content` response with just that slice instead of the whole file,
  enabling video/audio scrubbing and resumable downloads. Every file
  response now sets `Accept-Ranges: bytes`. Multi-range requests and
  `If-Range` aren't supported — see [RangeParser.bx](models/RangeParser.bx).
- Added `app.locals`/`res.locals` — two plain structs merged into every
  `res.render()` call's data (`app.locals` < `res.locals` < the explicit
  `data` argument, each overriding the last), so values a route doesn't set
  explicitly don't have to be threaded through every render() call by hand.
- Added `req.protocol`, `req.secure`, and `req.hostname` — same `trust
  proxy` trust model as `req.ip` (`X-Forwarded-Proto`/`X-Forwarded-Host`
  are only honored when `app.set("trust proxy", true)`), since BoxExpress's
  own `HttpServer` never terminates TLS itself.
- Added optional trailing route params (`/users/:id?`) — same restriction
  as the `*` wildcard, only supported as the final path segment, since
  matching one anywhere else would need real backtracking. See
  [Router.bx](models/Router.bx).

**0.1.12**
- **Fix:** `reloadOnChange` could silently kill the server on a failed
  restart. `_restartProcess()` called `close()` unconditionally before
  attempting to launch the replacement process — if that launch failed for
  any reason (a transient OS resource limit, a bad reconstructed command,
  etc.), the exception was uncaught on the watcher's daemon thread, and by
  then the old server was already torn down with nothing left running and
  no clear error tying the two together. Launching the replacement is now
  attempted *before* `close()` releases the port; a failed launch is caught,
  logged in red, and the current server is left running instead of dying
  with it. See [BoxExpress.bx](models/BoxExpress.bx).

**0.1.11**
- Added graceful process shutdown: `listen()` now registers a JVM shutdown
  hook so Ctrl-C/SIGTERM/normal exit runs `close()` (stopping the
  `httpServer` and, if running, the `reloadOnChange` watcher) instead of
  getting torn down mid-flight — previously the watcher's daemon thread
  could outlive a killed server. `close()` is now idempotent, and a bad
  port bind is caught and reported with a friendly message plus `exit(1)`
  instead of a raw stack trace.
- Added `res.dump(data)` — sends BoxLang's HTML `dump()` view of a variable
  as the response, round-tripped through a temp file since
  `dump(format="html")` only ever writes to the process console under
  BoxExpress's CLI-based `HttpServer`.
- Added colored console output (`AnsiColor.bx`): the access log gets a gray
  timestamp, a method colored by verb (`GET`=cyan, `POST`=green,
  `PUT`/`PATCH`=yellow, `DELETE`=red, everything else=magenta), and a dim
  IP; the `reloadOnChange` restart notice is cyan; fatal/error lines are
  red. Respects [`NO_COLOR`](https://no-color.org/) — set it to get plain
  text, e.g. when piping stdout to a file or log aggregator.
- Broadened test coverage: `PUT`/`PATCH`/`DELETE`/`all()` routing,
  `res.header()`/`type()`/`end()`/`sendBytes()`, `req.get()`/
  `rawExchange()`/`ip()` (including the `trust proxy` `X-Forwarded-For`
  case), `app.param()`, `res.redirect()` with an explicit status, and
  `close()` idempotency — plus a new `ProcessLifecycleSpec.bx` covering
  behavior that needs a real OS process boundary (port-conflict exit code,
  SIGTERM releasing the socket, `reloadOnChange` actually replacing the
  process). The test suite's `HttpClient.bx` helper moved from
  `HttpURLConnection` to `java.net.http.HttpClient`, since the former
  hard-rejects `PATCH`.

**0.1.10**
- **Fix:** the router's dispatch loop sometimes called `next()`/`_final()`
  with an explicit `null` error argument on the success path, instead of
  calling them with no argument at all. Express's own contract distinguishes
  the two — `next()` (no args) means "move on successfully," `next(err)`
  means "jump to error handling" — and code downstream that cared about the
  distinction (rather than just checking `isNull(err)`) could misread a
  `null`-argument call as an error. `next()`/`_final()` are now called
  argument-free whenever there's no error, matching Express. See
  [Router.bx](models/Router.bx).

**0.1.9**
- Added `boxExpressRouter()` — a global BIF wrapping `new
  bxModules.boxexpress.models.Router()`, mirroring Express's
  `express.Router()`. Brings `Router` in line with the rest of the public
  API surface, which was otherwise fully reachable through BIFs.

**0.1.8**
- Added `app.set("reloadOnChange", true)` — watches the working directory
  for `.bx`/`.bxs`/`.bxm` changes and restarts the process on save, using
  `java.nio.file.WatchService` and `ProcessHandle.current()` to replay the
  original launch command. See [FileWatcher.bx](models/FileWatcher.bx).

**0.1.7**
- **Fix:** `boxExpressStatic()` threw on any request to the mount root
  (`/`) instead of falling through to `next()`, returning a generic `500`
  for what should have been the app's own route handler. The middleware
  stripped the leading slash with `req.path.right( req.path.len() - 1 )`,
  which becomes `right(0)` for `req.path == "/"` — BoxLang's `right()`
  throws `"Count cannot be zero"` for a zero count (CF/Lucee just returns
  `""`). Switched to `mid()`, which handles it correctly.

**0.1.6**
- Added cookie-based sessions (`boxExpressSession()` / `Session` middleware,
  `req.session`, `req.sessionID`, `req.destroySession()`).

**0.1.5**
- Added `app.param(name, callback)` (Express-style route-param preprocessing),
  `app.route(path)` (chainable per-path route builder), and conditional GET
  support (`ETag`/`Last-Modified`/304) for `res.sendFile()` and static file
  serving.

**0.1.4**
- **Security fix:** the default `500` error handler echoed the raw exception
  message straight back to unauthenticated clients — this could leak
  internal details (absolute server file paths, driver errors, etc.) on any
  unhandled error, reachable via nothing more than a malformed body sent to
  the opt-in JSON parser. The default now sends a generic
  `"Internal Server Error"` message; the real message is still logged to
  stdout, and still returned in the response if the app opts in with
  `app.set("env", "development")`.

**0.1.3**
- **Security fix:** `res.sendFile()`/`res.download()` interpolated a
  caller-supplied `filename` into the `Content-Disposition` header
  unescaped, letting an attacker-controlled value (e.g. passed straight
  through from a query param) break out of the quoted `filename="..."`
  token and inject extra header parameters. Quote and control characters
  are now stripped before the value reaches the header.
- Added file upload (`boxExpressUpload()` / `Multipart` middleware,
  `req.files`) and download (`res.sendFile()`, `res.download()`) support.

**0.1.2**
- `listen()` now blocks the calling thread by default (`options.block`),
  matching Node's effective behavior — no more manual keep-alive loop
  required in consuming apps.
- Added request logging: every request gets a line on stdout as soon as
  it's received.

**0.1.1**
- Added Handlebars (`.hbs`) view rendering alongside the native `.bxm`
  engine.

## Scope (v1)

Routing, middleware chaining, mountable routers, params/query parsing, opt-in
JSON/urlencoded/multipart body parsing, static file serving, file
upload/download, cookie-based sessions, `.bxm`/Handlebars view rendering, and
default 404/500 handling.

## BoxLang gotchas worth knowing

`server`, `application`, `session`, `request`, `url`, `form`, `cookie`, and
`static` are reserved BoxLang scope names — assigning a variable one of those
names silently shadows the built-in scope instead of erroring at parse time,
which produces confusing runtime errors. This codebase avoids all of them
(e.g. `httpServer` instead of `server`, `formData` instead of `form`, the
static-file middleware class is named `StaticFiles` rather than `Static`).

`JSONSerialize()` (and therefore `res.json()`) only serializes literal
structs — not BoxLang class instances, even though `isStruct()` returns
`true` for one and its properties are freely readable via dot access.
`req` is a `Request` instance, so `res.json(req)` silently returns `{}`
rather than an error, no matter how many of `req.method`/`req.path`/
`req.query`/etc. actually have values — `property` declarations and a
`getMemento()` method don't change this. Build a plain struct out of the
fields you want instead:

```js
res.json( { method: req.method, path: req.path, query: req.query, params: req.params, body: req.body } )
```

The same applies to `res`, or any other class instance from this codebase
(or your own) — pass `res.json()` a struct, never an object.
