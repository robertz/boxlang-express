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
works identically if you'd rather be explicit about where it comes from. The
built-in middleware factories get the same treatment — `boxExpressJSON()`,
`boxExpressUrlencoded()`, `boxExpressStatic()`, `boxExpressUpload()` (see
[Middleware](#middleware) below) — but `Router` is only reachable via
`new bxModules.boxexpress.models.Router()`, since Express doesn't give
`Router` its own top-level global either.

## Install

Published on [ForgeBox](https://www.forgebox.io/) as `boxlang-express`:

```bash
box install boxlang-express
```

That drops it into a project's local `boxlang_modules/boxexpress/` (or use
`box install boxlang-express --system` for the runtime-wide
`~/.boxlang/modules/`). The mapping (`bxModules.boxexpress`) is registered by
the BoxLang runtime the moment it discovers the module — but that discovery
is relative to the **process's current working directory** (no upward
search), so `boxlang` has to be invoked from the directory containing
`boxlang_modules/`, same as `node_modules` resolution rules of thumb in
npm-land.

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

- `app.get/post/put/patch/delete/all(path, ...handlers)`
- `app.use(handler)` / `app.use(path, handler)` / `app.use(path, router)`
- `app.param(name, callback)` — see [Route params](#route-params-appparamname-callback) below
- `app.route(path)` — see [Chainable routes](#chainable-routes-approutepath) below
- `app.set(name, value)` / `app.getSetting(name)`
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

Every request gets a line on stdout as soon as it's received —
`[2026-08-10 10:45:41] GET /users/42 127.0.0.1` — there's no setting to turn
this off yet.

#### Auto-restart on file change (`app.set("reloadOnChange", true)`)

```js
app.set( "reloadOnChange", true )
```

Dev-only convenience — watches the current working directory (recursively,
skipping `boxlang_modules/`, `node_modules/`, `.git`, and other dot-dirs) for
`.bx`/`.bxs`/`.bxm` changes and restarts the process on save. There's no hot
reload inside a running JVM once BoxLang has compiled your classes, so this
works by closing the current server, replaying the exact original launch
command (via `ProcessHandle.current()`, so it works whatever the entry
script is named or however it was launched), and exiting — same net effect
as running under `entr -r` or `nodemon`, just without the extra dependency.
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
`req.headers`, `req.get(name)`, `req.cookies`, `req.ip`, `req.body` (populated
by body-parsing middleware, empty struct otherwise).

`req.ip` is always the direct TCP peer by default — safe, since a client
can't spoof it, but wrong behind a reverse proxy (it'll report the proxy's
IP). Opt in to trusting `X-Forwarded-For` with `app.set("trust proxy", true)`,
same as Express; leave it off (the default) unless you actually control the
proxy in front of this, since with it on, anyone who can reach the app
directly can forge their reported IP.

### Response

`res.status(code)`, `res.set(name,val)` / `res.header(...)`, `res.type(mime)`,
`res.send(data)`, `res.json(data)`, `res.sendStatus(code)`,
`res.redirect(url, code=302)`, `res.cookie(name,val,options)`,
`res.sendBytes(bytes, contentType)`, `res.sendFile(path, options)`,
`res.download(path, filename)`, `res.end(data)`, `res.render(view, data)`.

Calling a second terminal method (`send`/`json`/`redirect`/`end`/`sendBytes`/
`sendFile`/`download`/`render`) on the same response throws — mirrors
Express's "headers already sent".

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

### Middleware

Handlers are `(req, res, next) => {...}`. Call `next()` to continue to the
next matching layer, or `next(err)` to jump to error-handling middleware.
Error-handling middleware is any handler with **4** parameters —
`(err, req, res, next) => {...}` — detected the same way Express does
(`fn.length === 4`), and should be registered last.

Built-in middleware factories (opt-in, same philosophy as `express.json()`),
each with its own global BIF mirroring the Express function of the same name:

```js
app.use( boxExpressJSON() )        // parses application/json bodies into req.body
app.use( boxExpressUrlencoded() )  // parses application/x-www-form-urlencoded bodies
app.use( "/public", boxExpressStatic( expandPath( "./public" ) ) )
app.use( boxExpressSession() )     // cookie-based sessions, req.session
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

## A BoxLang gotcha worth knowing

`server`, `application`, `session`, `request`, `url`, `form`, `cookie`, and
`static` are reserved BoxLang scope names — assigning a variable one of those
names silently shadows the built-in scope instead of erroring at parse time,
which produces confusing runtime errors. This codebase avoids all of them
(e.g. `httpServer` instead of `server`, `formData` instead of `form`, the
static-file middleware class is named `StaticFiles` rather than `Static`).
