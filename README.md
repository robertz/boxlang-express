# BoxExpress

[![Tests](https://github.com/robertz/boxlang-express/actions/workflows/test.yml/badge.svg)](https://github.com/robertz/boxlang-express/actions/workflows/test.yml)

An Express.js-style web framework for BoxLang. Runs as a standalone HTTP server
(backed by [Undertow](https://undertow.io/), with a virtual thread per
request) — no servlet container required.

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
`boxExpressCsrf()`, `boxExpressStomp()` (see
[Middleware](docs/middleware.md)) — and
`boxExpressRouter()`, wrapping `new
bxModules.boxexpress.models.Router()` and mirroring Express's own
`express.Router()`.

## Documentation

The API reference lives in [docs/](docs/), one page per feature — see
[API reference](#api-reference) below. For guides — getting started, configuration, routing, middleware, request/response,
views, sessions, static files & uploads, error handling, and process
lifecycle, each with runnable examples — see
[kisdigital.com/projects/boxlang-express/docs/getting-started](https://kisdigital.com/projects/boxlang-express/docs/getting-started).

For design decisions and the reasoning behind them, see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). To contribute, see
[CONTRIBUTING.md](CONTRIBUTING.md).

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


## API reference

Core:

- [App](docs/app.md) — routes, settings, `listen()` / `close()`
- [Router](docs/routing.md) — mountable sub-apps, route params, chainable routes
- [Request](docs/request.md)
- [Response](docs/response.md) — including range requests
- [Views](docs/views.md) — `res.render()`, `app.locals` / `res.locals`
- [Error handling](docs/error-handling.md) — default `404`/`500` responses

Middleware:

- [Middleware](docs/middleware.md) — handler shape, body parsers, static files
- [File uploads](docs/uploads.md) — `boxExpressUpload()`
- [Sessions](docs/sessions.md) — `boxExpressSession()`, durable stores
- [Security headers](docs/helmet.md) — `boxExpressHelmet()`
- [CORS](docs/cors.md) — `boxExpressCors()`
- [Rate limiting](docs/rate-limiting.md) — `boxExpressRateLimit()`
- [CSRF protection](docs/csrf.md) — `boxExpressCsrf()`

Realtime and background work:

- [Server-Sent Events](docs/sse.md) — `res.sse()`
- [WebSockets](docs/websockets.md) — `app.ws()`
- [STOMP](docs/stomp.md) — `boxExpressStomp()`
- [Scheduler](docs/scheduler.md) — `app.schedule()`
- [Cluster support](docs/cluster.md) — `app.getClusterManager()`

Operations:

- [Testing without a server](docs/testing.md) — `app.inject()`
- [Observability](docs/observability.md) — request IDs, `app.metrics()`
- [Health checks and graceful shutdown](docs/health-and-shutdown.md)
- [Auto-restart on file change](docs/auto-restart.md)

Project:

- [BoxLang gotchas worth knowing](docs/gotchas.md)
- [Hacking on BoxExpress itself](docs/development.md) — running the example and the tests
- [Changelog](CHANGELOG.md)

## Scope (v1)

Routing, middleware chaining, mountable routers, params/query parsing, opt-in
JSON/urlencoded/multipart body parsing, static file serving, file
upload/download, cookie-based sessions, `.bxm` view rendering, and
default 404/500 handling.
