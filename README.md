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
three built-in middleware factories get the same treatment —
`boxExpressJSON()`, `boxExpressUrlencoded()`, `boxExpressStatic()` (see
[Middleware](#middleware) below) — but `Router` is only reachable via
`new bxModules.boxexpress.models.Router()`, since Express doesn't give
`Router` its own top-level global either.

## Install

Not yet published to ForgeBox — for now, either symlink or copy this repo
into a consuming project's `boxlang_modules/boxexpress/`:

```bash
mkdir -p boxlang_modules
ln -s /path/to/boxlang-express boxlang_modules/boxexpress
```

The mapping (`bxModules.boxexpress`) is registered by the BoxLang runtime the
moment it discovers the module — but that discovery is relative to the
**process's current working directory** (no upward search), so `boxlang` has
to be invoked from the directory containing `boxlang_modules/`, same as
`node_modules` resolution rules of thumb in npm-land.

Once published, this would just be `box install boxlang-express`.

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
- `app.set(name, value)` / `app.getSetting(name)`
- `app.listen(port, callback)` — starts the server, returns `app` immediately (non-blocking)
- `app.close()` — stops the server

`listen()` doesn't block: Node keeps a CLI process alive via its event loop,
but BoxLang's CLI runtime has no equivalent, so a `boxlang server.bxs`
long-running process needs to block its own main thread after calling
`listen()` (see the `while(true) { sleep(1000) }` at the end of
`examples/server.bxs`) or the process will exit immediately after starting
the server.

### Router (mountable sub-app)

Same routing methods as `app`. Create one with `new bxModules.boxexpress.models.Router()` and mount
it with `app.use("/api", apiRouter)` — request paths are matched relative to
the mount point, exactly like Express's `express.Router()`.

### Request

`req.method`, `req.path`, `req.originalUrl`, `req.query`, `req.params`,
`req.headers`, `req.get(name)`, `req.cookies`, `req.ip`, `req.body` (populated
by body-parsing middleware, empty struct otherwise).

### Response

`res.status(code)`, `res.set(name,val)` / `res.header(...)`, `res.type(mime)`,
`res.send(data)`, `res.json(data)`, `res.sendStatus(code)`,
`res.redirect(url, code=302)`, `res.cookie(name,val,options)`,
`res.sendBytes(bytes, contentType)`, `res.end(data)`, `res.render(view, data)`.

Calling a second terminal method (`send`/`json`/`redirect`/`end`/`sendBytes`/
`render`) on the same response throws — mirrors Express's "headers already
sent".

### Views (`res.render`)

Renders a `.bxm` template (BoxLang's server-page format — think `.cfm`) from a
configured views directory and sends the result as `text/html`:

```js
app.set( "views", expandPath( "./views" ) )

app.get( "/greet/:name", ( req, res ) => {
	res.render( "greeting", { name: req.params.name, age: 30 } )
} )
```

`views/greeting.bxm`:

```html
<bx:output>
<h1>Hello, #data.name#!</h1>
<p>You are #data.age# years old.</p>
</bx:output>
```

Two things worth knowing:

- `data` is whatever struct you pass as the second argument to `render()` —
  reference its keys as `data.whatever` in the template.
- `#var#` interpolation in a `.bxm` file only happens inside a `<bx:output>`
  block, exactly like `<cfoutput>` in classic CFML — plain text outside one is
  left as literal `#...#`, unevaluated.

`render()` throws if `app.set("views", ...)` was never called.

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

If no route matches, a default `404` JSON response is sent. If a handler
throws (or calls `next(err)`) and no error-handling middleware is registered,
a default `500` JSON response is sent.

## Tests

[TestBox](https://testbox.ortusbooks.com/) specs, run headlessly (no server
needed — this runs the same way `examples/server.bxs` does, just against
TestBox instead of an HTTP request):

```bash
boxlang run-tests.bxs
```

Exits non-zero on any failure/error, so it's CI-friendly as-is. Structure:

- `tests/specs/RouterSpec.bx` — unit tests for path matching and the
  middleware/`next()` chain, using lightweight fake `req`/`res` structs
  instead of a real `HttpExchange` (Router never touches anything else).
- `tests/specs/BoxExpressIntegrationSpec.bx` — spins up a real app on
  `localhost:4321` in `beforeAll()` and hits it with real HTTP requests
  (`tests/helpers/HttpClient.bx`, a thin `HttpURLConnection` wrapper),
  exercising `Request`/`Response`/`BodyParsers`/`StaticFiles`/`render()`
  together, plus a concurrency check using `bx:thread`.
- `tests/fixtures/` — a static file and a `.bxm` view the integration spec
  serves/renders.

Two BoxLang-specific things that shaped how these are written:

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

## Scope (v1)

Routing, middleware chaining, mountable routers, params/query parsing, opt-in
JSON/urlencoded body parsing, static file serving, `.bxm` view rendering, and
default 404/500 handling. Not included (possible future extensions):
sessions and multipart file-upload parsing.

## A BoxLang gotcha worth knowing

`server`, `application`, `session`, `request`, `url`, `form`, `cookie`, and
`static` are reserved BoxLang scope names — assigning a variable one of those
names silently shadows the built-in scope instead of erroring at parse time,
which produces confusing runtime errors. This codebase avoids all of them
(e.g. `httpServer` instead of `server`, `formData` instead of `form`, the
static-file middleware class is named `StaticFiles` rather than `Static`).
