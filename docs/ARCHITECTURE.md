# BoxExpress: Architecture & Design Decisions

For new contributors. This covers *why* the codebase is shaped the way it is —
the README covers *what* the API does. Read this once before your first PR;
it'll save you from re-deriving decisions that were already made deliberately,
and from re-triggering bugs that were already found and fixed once.

## What this is

An Express.js-style web framework for BoxLang. It is a standalone HTTP
server — not a servlet container, not a CFML-tag-page server. `boxlang
app.bxs` starts a real process that binds a port and stays up, the same
mental model as `node server.js`. This is a foundational choice, not an
implementation detail: BoxLang's bare CLI runtime has no concept of an HTTP
request at all (see "Why not `boxlang-web-support`?" below), so this project
exists specifically to build that layer, Express-shaped, from scratch.

## The server transport: Undertow, not the JDK's `HttpServer`

**Original state:** `com.sun.net.httpserver.HttpServer`, bundled with every
JDK, zero extra dependencies.

**Current state:** [Undertow](https://undertow.io/), vendored in `libs/`.

**Why the switch:** `com.sun.net.httpserver` lives under `com.sun.*`, not
`java.*` — it's not a stable public API, and Oracle's own docs describe it
as a lightweight implementation not intended for production use. It's also
HTTP/1.1-only with no path to HTTP/2 or WebSockets. Undertow is what
BoxLang's own official embedded server (MiniServer) uses internally too —
confirmed directly by reading MiniServer's own `build.gradle`, which
depends on `io.undertow:undertow-core` the same way this project does. This
wasn't a "we found something fancier" decision; it was converging on the
same choice BoxLang's own team already made.

**How the switch was validated, not just asserted:** the migration ran in
gated phases — spike the classloading first (does Undertow even load
cleanly under BoxLang's module isolation?), then extract a server-lifecycle
seam wrapping the *existing* JDK behavior unchanged, then build the
Undertow implementation behind that same seam, then run the **entire**
existing test suite against both engines and diff the *live* responses
against each other (not just re-check each engine against a hardcoded
expectation — see `git log` for the Undertow-adapter Phase 5 work), then
load-test both under `ab`. Only once all of that came back clean did the
JDK engine get deleted.

**The seam that got built, then deleted:** for a while there was an
`HttpServerAdapter` interface with two implementations and a
`server.engine` config setting to choose between them. Once the decision
was made (keep Undertow, JDK's not coming back), that whole abstraction —
the interface, the constructor-injection point, the config setting, the
tests that existed only to exercise engine-swapping — was torn out
deliberately. `BoxExpress.bx`'s `listen()`/`close()` talk to Undertow
directly now. **This is a pattern worth internalizing for this codebase:**
build an abstraction when there's a real, current reason for it (comparing
two engines); delete it the moment that reason is gone (one engine, no
seam to justify). Don't leave speculative pluggability lying around "in
case it's needed later."

## Virtual-thread-per-request

Every request gets dispatched onto its own JVM virtual thread
(`UndertowVirtualThreadHandler.bx`), not Undertow's default bounded XNIO
worker pool. This is deliberate and was **caught as a regression**, not
designed in from day one on the Undertow side: the original JDK adapter
used `Executors.newVirtualThreadPerTaskExecutor()`, and the first version
of the Undertow adapter silently dropped that in favor of Undertow's
`BlockingHandler` (which hands work to XNIO's own bounded platform-thread
pool instead). A code review caught the mismatch between the actual
behavior and what the README still claimed ("a virtual thread per
request"), and it got fixed by dispatching each request through
`HttpServerExchange.dispatch(Executor, HttpHandler)` onto a real virtual
thread — confirmed with a throwaway script that checked
`Thread.currentThread().isVirtual()` from inside a route handler before
believing it.

**Why this matters for anything you build:** every route handler, every
middleware function, runs on a cheap, disposable thread that's safe to
block. `sleep()`, a blocking DB call, a long loop — none of it starves a
shared worker pool. This is also why `res.sse()` (see below) doesn't need
the `async`/`timeout` complexity BoxLang's own `SSE()` BIF has: that BIF's
underlying runtime uses a bounded thread pool and has to worry about tying
one up; this framework already gives every request a disposable thread by
default.

## Never terminates TLS — and everything that follows from it

BoxExpress assumes a reverse proxy (nginx, Caddy, Cloudflare, a cloud
load balancer) always sits in front of it. This single assumption cascades
into several other decisions that might otherwise look like gaps:

- **No built-in HTTPS/TLS.** `req.protocol`/`req.secure` are always
  `"http"`/`false` unless `trust proxy` is on *and* the request carries
  `X-Forwarded-Proto: https` — the shape you'd see behind a TLS-terminating
  proxy.
- **No HTTP/2.** The proxy in front already speaks it to clients; adding
  it here would mean either terminating TLS ourselves (a real reversal of
  the above) or supporting cleartext HTTP/2 for a backend hop that's
  already fast and not the bottleneck.
- **No response-compression middleware.** Scoped in detail (see
  `plans/compression.md`, kept for reference even though the answer was
  "no") — Cloudflare already compresses at the edge for the leg that
  actually matters (client-facing). The origin↔edge leg isn't
  bandwidth-constrained. Explicitly deprioritized for *this* project's
  actual deployment, not a "not implemented yet."
- **`req.ip` defaults to the direct TCP peer**, never a header, unless you
  opt in with `app.set("trust proxy", true)`. And even that boolean isn't
  always enough — `app.set("trust proxy header", "DO-Connecting-IP")`
  exists because `X-Forwarded-For` gets *appended to*, not replaced, by
  DigitalOcean App Platform + Cloudflare in front of it — confirmed
  directly against that real stack, not assumed from docs. A client can
  prepend a forged entry to `X-Forwarded-For` and have it survive to the
  app. The platform's own edge-set header (`DO-Connecting-IP`,
  `CF-Connecting-IP`) is checked first when configured, since it reflects
  what the edge actually observed, not anything the client sent.

If you're deploying this somewhere without a proxy in front, several of
these defaults stop being safe. That's a real constraint, not a
theoretical one.

## The exchange-adapter pattern (`models/adapters/`)

`Request.bx` and `Response.bx` never touch Undertow's
`HttpServerExchange` directly except through one narrow, documented
escape hatch (`req.rawExchange()`). Everything else goes through
`UndertowExchangeAdapter.bx`'s contract: `getMethod()`, `getPath()`,
`getRequestHeaders()`, `setHeader()`, `sendBody()`, etc.

This dates back to when there were two engines and the adapter genuinely
had to be engine-neutral. It survived the JDK adapter's removal on
purpose — not because pluggability is still needed (it isn't), but because
it keeps `Request`/`Response` from being littered with raw Undertow API
calls, which makes them easier to read and change independent of exactly
how Undertow's API is shaped. A narrower reason than "we might swap
engines again," but a real one.

## Response is buffered by default; SSE is the one deliberate exception

Every terminal method on `Response.bx` — `send()`, `json()`, `sendFile()`,
all of them — builds one byte array and hands it to the adapter in a
**single** `sendBody()` call, funneled through one private method,
`_end()`. This is the whole model: compute the full response, send it
once.

`res.sse()` breaks that model on purpose, because Server-Sent Events
genuinely need an open connection with incremental writes over time — not
a small extension of `_end()`, a parallel response mode that bypasses it
entirely, writing straight to the exchange's raw `OutputStream`
(`getResponseOutputStream()` on the adapter). If you're ever tempted to add
another streaming-shaped feature, this is the seam to hook into, not a
retrofit of `_end()`.

Two things worth knowing if you touch `SseEmitter.bx`:

- **It's not a wrapper around BoxLang's own `SSE()` BIF.** Confirmed
  directly that `SSE()` isn't callable from a BoxExpress route handler at
  all (`Function [SSE] not found`) — it lives in the
  `boxlang-web-support` runtime module that MiniServer/CommandBox/servlet
  deployments load, which this project deliberately doesn't (see "What
  this is" above). The `emitter.send()`/`comment()`/`close()`/`isClosed()`
  API shape was copied on purpose, since it's a proven design — the
  implementation underneath is entirely new.
- **`send()`/`comment()`/`close()` are safe to call from a different
  thread than the one that opened the connection**, via a per-emitter
  lock — the real use case is a broadcast pattern (stash the emitter in a
  shared struct, push to it from an unrelated route later). Verified under
  actual concurrent writes from five threads, not just reasoned about.
- **`event`/`id`/`comment()`'s `text` strip embedded `char(13)`/`char(10)`
  before being written.** This was a real security finding, not a
  precaution added preemptively: `data` was already newline-safe (split
  per-line), but `event`/`id` weren't, and a caller piping request-derived
  input into either (a channel name, a correlation ID — a reasonable thing
  to want) let a client inject a fabricated SSE event into every other
  connected client's stream. Same class of bug as CRLF injection in a
  response header. **The lesson generalizes:** any new sink that
  concatenates caller-supplied strings into a wire protocol needs to be
  safe by construction, not safe-if-used-correctly — assume it'll
  eventually be fed request-derived data, because it will be.

## The one piece of compiled Java (`java-src/boxexpress/ws/`)

Everything in this codebase is pure BoxLang plus off-the-shelf vendored
jars — except this one small shim, and it exists for a specific,
confirmed reason, not out of convenience.

Undertow's intended extension point for receiving WebSocket messages,
`io.undertow.websockets.core.AbstractReceiveListener`, is an **abstract
Java class** with protected hook methods (`onFullTextMessage`, etc.), not
an interface. Everywhere else in this codebase that needs to satisfy a
Java type from BoxLang, it's `createDynamicProxy` against an *interface*
(`HttpHandler`, `WebSocketConnectionCallback`, `ChannelListener`, all of
`models/adapters/`) — that mechanism only works for interfaces. Two ways
around that were tried and confirmed not to work, not just assumed:

1. BoxLang's own documented `extends="java:X"` feature for subclassing a
   Java class. Failed even on BoxLang's own docs example
   (`java.io.FilterInputStream`) with a constructor-argument-forwarding
   bug (`method 'void <init>()' not found` despite calling
   `super.init(arg)`), and failed earlier still — at class resolution —
   for `AbstractReceiveListener` specifically.
2. Implementing the lower-level `org.xnio.ChannelListener` interface
   directly (which *is* proxyable) and reading frames manually.
   `handleEvent(channel)` fires correctly, but the only way to actually
   consume the pending frame, `channel.receiveFrame()`, is a `protected`
   method — BoxLang's Java interop can't invoke it. Nothing ever gets
   consumed; `handleEvent` just re-fires in a busy loop.

`BoxWebSocketListener.java` does the real Java-side subclassing once, and
re-exposes every event through a plain interface,
`WebSocketMessageHandler`, that BoxLang code implements the normal way.
Rebuild it with `java-src/build.sh` if you ever touch the `.java` files;
the compiled output is what actually ships, vendored in `libs/` like
every other dependency.

**One thing that had to be fixed on the first pass, not designed in
correctly from the start:** the first version called straight into the
BoxLang handler from inside Undertow's receive-listener callback, which
runs on Undertow's I/O thread by default — same mistake already made and
fixed once for the plain HTTP request path (see "Virtual-thread-per-
request" above). A handler doing any blocking work there (a blocking send
back to the client, in the shim's own test) intermittently reset the
connection instead of throwing a clean, loud error — worse than the HTTP
case, because it was flaky rather than deterministic, and took timing
instrumentation to actually pin down. `BoxWebSocketListener` now dispatches
every callback onto its own virtual thread before it ever reaches BoxLang
code, matching the one-thread-per-unit-of-work model the rest of this
project already uses.

If you're extending this shim, treat any new callback the same way: never
call into BoxLang code directly from inside an Undertow-owned callback
without dispatching off whatever thread Undertow handed you first.

`app.ws(path, callback)` and `WebSocketConnection.bx` are built on top of
this — `models/adapters/WsConnectionCallback.bx` is the one place that
constructs a `BoxWebSocketListener`, looks up which registered handler
owns a connection's path (stripped of its query string —
`WebSocketHttpExchange.getRequestURI()` includes it, confirmed directly,
unlike `HttpServerExchange.getRequestPath()` used for the plain HTTP
path), and hands the connection to that handler before resuming receives.
`models/adapters/WsUpgradeRouter.bx` is the pre-filter in front of
Undertow's dispatch that decides, per request, whether to route to the
WebSocket handshake handler or straight through to the unchanged HTTP
chain — see the `app.ws()` section of the README for the request-flow
details; this is the architectural "why," not a repeat of the "what."

## `onBeforeSend()` — a narrow, deliberate hook

`res.onBeforeSend(callback)` runs a callback once, synchronously, the
instant before headers actually flush — after every downstream
middleware/handler has run, but before `_end()` commits anything. It
exists because `Session.bx`'s `saveUninitialized` option needs to decide
*whether to send a cookie at all* based on what happened during the
request, but by the time a middleware's own `next()` call returns to its
frame, a terminal handler further down the chain has usually already
flushed headers — too late to add one. Same problem Express's
`express-session` solves by monkey-patching `res.end`; done explicitly
here since BoxLang can't intercept that way.

If you need "the very last chance to influence headers before they're
sent," this is the hook. It is not currently wired to see or replace body
bytes — only header-level decisions.

## Middleware philosophy: mirror the proven npm package, don't invent

`Helmet.bx`, `Cors.bx`, `RateLimit.bx`, `Csrf.bx`, `Session.bx` each
mirror a specific, well-known npm package's option names and defaults —
`helmet`, `cors`, `express-rate-limit`, `csurf`, `express-session` —
deliberately, down to option names like `resave`/`saveUninitialized`. This
isn't laziness; it means the *design* risk (what should this option be
called, what should the default be, what's the right trade-off) was
already resolved by a widely-used package, and porting it means only the
*implementation* has to be gotten right in BoxLang. When you add new
middleware, look for what the Express ecosystem already settled on before
inventing a new shape.

Every middleware factory also gets a thin global BIF wrapper
(`boxExpressJSON()`, `boxExpressHelmet()`, etc.) registered automatically
by the module system — the `new bxModules.boxexpress.models.middleware.X()`
form still works identically; the BIF is purely ergonomic, mirroring how
`require('express')()` feels in Node.

## Path-traversal containment: one pattern, used three times

`StaticFiles.bx`, `Response.render()`, and `Response.sendFile()`'s
`options.root` all use the same check: canonicalize the requested path,
canonicalize the configured root, require the candidate's *real* path
(symlinks resolved) to start with the root's real path, throw otherwise.
If you add a fourth feature that resolves a user-influenceable path
against a directory, reuse this pattern rather than writing a new one —
it's already been hardened against the obvious bypasses (symlinks,
relative segments) and is exercised by existing tests.

## Error handling defaults are security defaults

A thrown/unhandled error gets a generic `"Internal Server Error"` message
in the response — the real message is always logged server-side, but never
echoed to an unauthenticated client unless `app.set("env", "development")`
is on. This was a **security fix**, not a design-from-scratch choice (see
the 0.1.4 changelog entry: the original default leaked internal details —
file paths, driver errors — to any client that could trigger an unhandled
error). The pattern is now consistent everywhere an error reaches a
response: route/middleware errors, `Request`/`Response` construction
failures, the works. If you add a new error path, it needs to honor `env`
the same way or it's a regression of that fix.

## BoxLang landmines this codebase has already found (so you don't have to)

These aren't BoxExpress design decisions — they're real BoxLang engine
behaviors that surprised people while building this, each confirmed with a
minimal repro before being worked around. Knowing them up front will save
you real debugging time:

- **`server`, `application`, `session`, `request`, `url`, `form`,
  `cookie`, `static` are reserved BoxLang scope names.** Using one as a
  variable name silently shadows the built-in scope instead of erroring —
  confusing runtime failures follow. This codebase avoids all of them
  (`undertowServer` not `server`, `formData` not `form`, etc.).
- **`chr()` isn't a BoxLang BIF — it's `char()`.** Looks like it should
  exist (CFML tradition), throws "Function [chr] not found" if you use it.
- **`left()`/`right()` throw `"Count cannot be zero"`** on a zero count,
  where CF/Lucee just return `""`. Anywhere you're slicing a string by a
  computed length that could legitimately be zero (an empty query value,
  an empty cookie value, an empty multipart field), use `mid()` instead.
  This bug class was found and fixed in at least seven places across this
  codebase (see the 0.1.15 changelog entry) — check `mid()` usage nearby
  before adding a new `left()`/`right()` call.
- **Top-level `.bxs` scripts have no `local` scope.** `var` inside a
  top-level `for` loop throws `"Scope [local] is not available"`.
- **Inside a closure, `arguments` doesn't inherit the parent function's
  arguments.** Referencing `arguments.x` in a closure when `x` wasn't
  actually passed *to that closure* is a hard error, not `undefined`.
- **`bx:thread` bodies don't close over an enclosing closure's local
  variables.** A `bx:thread` block inside a `( req, res ) => {...}` route
  handler can't see `req`/`res`/any other local var by just referencing
  it — pass it as an unquoted thread attribute (`bx:thread myVar=req
  {...}`, then `attributes.myVar` inside) or it throws "key not located in
  any scope."
- **`throw(message=, type=, cause=)`'s `cause` parameter doesn't actually
  attach `e.cause`** — confirmed with a standalone repro. This codebase's
  plain `throw(message=, type=)` convention (no `cause=`) is deliberate,
  not an oversight.
- **`RequestBoxContext.getConfig()` memoizes its merged config on first
  read and never invalidates it.** If you register a new BoxLang mapping
  (`getBoxRuntime().getConfiguration().registerMapping(...)`) at runtime,
  a context that already read its config before that point won't see it
  until you call `getBoxContext().clearConfigCache()`. This bit
  `res.render()` twice on BoxLang 1.17.0 — once for the missing mapping,
  again for this stale-cache issue — before both were fixed.
- **BoxLang 1.17.0 stopped resolving a bare absolute `include` path**
  unless it's backed by a registered mapping — deliberate hardening
  against implicitly trusting an absolute path. If you add a new feature
  that does a dynamic `include` on a computed absolute path, it needs its
  own registered mapping (see `BoxExpress.bx`'s `set()` handling of
  `"views"` for the pattern) — don't ask upstream BoxLang to bypass the
  check; that was tried and rejected for good reason
  ([ortus-boxlang/BoxLang#610](https://github.com/ortus-boxlang/BoxLang/issues/610)).
- **`JSONSerialize()`/`res.json()` only serializes literal structs**, not
  BoxLang class instances — even though `isStruct()` returns `true` for
  one. `res.json(req)` silently returns `{}`. Build a plain struct of the
  fields you actually want.

## Testing philosophy

- **Real HTTP requests against a live server, not mocks**, for anything
  that isn't pure routing logic (`RouterSpec.bx` is the one exception,
  using lightweight fake `req`/`res` structs since `Router` never touches
  a real exchange anyway).
- **Subprocess tests for anything that needs a real OS boundary**
  (`ProcessLifecycleSpec.bx`) — a clean exit on port conflict, `SIGTERM`
  releasing the socket, `reloadOnChange` actually replacing the running
  process. These can't be tested in-process because `listen()` calls
  `System.exit(1)` on a port conflict, which would kill the test runner
  itself if triggered in the same JVM — a mistake made and caught more
  than once while building this suite; if you write a new test touching
  that path, use the adapter/subprocess directly, never `app.listen()`.
- **Verify empirically before writing a permanent test**, and often before
  writing the implementation at all — a recurring pattern in this
  project's history is a throwaway `.bxs` script proving a claim (a real
  virtual thread, a disconnect getting detected, injected bytes actually
  landing inert) before it's trusted enough to assert in a real spec.
- **A concurrency claim gets a concurrency test.** "This is thread-safe"
  is only trusted in this codebase once something has actually hammered it
  from multiple threads and the output was checked byte-for-byte.

## Release process

`ModuleConfig.bx`'s `this.version` tracks the **next unreleased**
version — bumped as part of the PR that adds the feature/fix it
describes, alongside a new heading in the README's Changelog section.
`box.json`'s version only changes via `box bump`/`box forgebox publish`,
run separately, when an actual release happens — the two are allowed to
be out of sync in between. Every changelog entry names the file(s)
changed and explains the *why*, not just the *what* — that's the primary
place decision rationale is recorded in this codebase, so when in doubt,
read backward through it before asking.

## Honest open risks (not resolved, worth knowing)

- **Review is currently opt-in, not a required gate.** More than one real
  bug in this codebase (a silent virtual-thread regression, an
  inefficiency, the SSE newline-injection vulnerability) shipped in a
  merged PR and was only caught because someone separately asked for a
  review afterward. Nothing currently blocks a PR from merging without
  one.
- **Versioning has had at least one real inconsistency** — a fix branch
  created from a stale point in git history got merged forward with a
  changelog entry that reused an already-used version number, producing a
  README that now cross-references a version incorrectly. Small, but real
  evidence the branch → PR → merge → version-bump process isn't fully
  airtight under concurrent branches.
- **This project depends on a still-stabilizing language runtime.**
  BoxLang 1.17.0 introduced a breaking change to `include` resolution with
  no advance-notice migration path found in advance — it was discovered by
  `render()` breaking in production-adjacent testing. Expect more of this
  as BoxLang itself matures; it's not a BoxExpress defect when it happens,
  but it will happen again.
- **Scope is "sufficient for this project's real deployment," not
  "general-purpose framework parity."** Reasonable and explicit (see the
  compression scope doc), but don't assume feature parity with Express's
  broader ecosystem just because a given feature mirrors an npm package —
  check what's actually been ported before relying on something.

## Where to look next

- `README.md` — full API reference, plus the complete changelog (the
  primary record of *why*, chronologically).
- `plans/` — scoped-but-not-built feature designs (WebSockets, compression)
  kept as reference for when/if they're picked back up.
- `models/` — read `BoxExpress.bx` and `Router.bx` first; everything else
  hangs off those two.
- `tests/specs/` — when in doubt about whether a behavior is intentional,
  check whether there's a test asserting it. If there is, that behavior is
  load-bearing; don't change it without updating the test deliberately.
