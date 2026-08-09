# BoxExpress

An Express.js-style web framework for BoxLang. Runs as a standalone HTTP server
(backed by the JDK's built-in `com.sun.net.httpserver.HttpServer`, with a
virtual thread per request) — no servlet container required.

```js
app = new "src/BoxExpress"()

app.get( "/", ( req, res ) => {
	res.send( "Hello World" )
} )

app.listen( 3000, ( port ) => {
	println( "listening on #port#" )
} )
```

## Install / run

Requires BoxLang (tested against 1.15.0) on the JVM. No external dependencies.

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
```

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

Same routing methods as `app`. Create one with `new "src/Router"()` and mount
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
`res.sendBytes(bytes, contentType)`, `res.end(data)`.

Calling a second terminal method (`send`/`json`/`redirect`/`end`/`sendBytes`)
on the same response throws — mirrors Express's "headers already sent".

### Middleware

Handlers are `(req, res, next) => {...}`. Call `next()` to continue to the
next matching layer, or `next(err)` to jump to error-handling middleware.
Error-handling middleware is any handler with **4** parameters —
`(err, req, res, next) => {...}` — detected the same way Express does
(`fn.length === 4`), and should be registered last.

Built-in middleware factories (opt-in, same philosophy as `express.json()`):

```js
bodyParsers = new "src/middleware/BodyParsers"()
app.use( bodyParsers.json() )        // parses application/json bodies into req.body
app.use( bodyParsers.urlencoded() )  // parses application/x-www-form-urlencoded bodies

staticFiles = new "src/middleware/StaticFiles"()
app.use( "/public", staticFiles.serve( expandPath( "./public" ) ) )
```

If no route matches, a default `404` JSON response is sent. If a handler
throws (or calls `next(err)`) and no error-handling middleware is registered,
a default `500` JSON response is sent.

## Scope (v1)

Routing, middleware chaining, mountable routers, params/query parsing, opt-in
JSON/urlencoded body parsing, static file serving, and default 404/500
handling. Not included (possible future extensions): sessions, multipart
file-upload parsing, and view-engine/template rendering.

## A BoxLang gotcha worth knowing

`server`, `application`, `session`, `request`, `url`, `form`, `cookie`, and
`static` are reserved BoxLang scope names — assigning a variable one of those
names silently shadows the built-in scope instead of erroring at parse time,
which produces confusing runtime errors. This codebase avoids all of them
(e.g. `httpServer` instead of `server`, `formData` instead of `form`, the
static-file middleware class is named `StaticFiles` rather than `Static`).
