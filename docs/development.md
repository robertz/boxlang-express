# Hacking on BoxExpress itself

Requires BoxLang (tested against 1.15.0) on the JVM. Undertow and its
transitive runtime dependencies (XNIO, JBoss Logging, etc.) are vendored in
`libs/` — nothing to install for those. TestBox is the only thing that
needs pulling in, and only for the test suite (`box install`).

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
  instead of a real exchange (Router never touches anything else).
- `tests/specs/BoxExpressIntegrationSpec.bx` — spins up a real app on
  `localhost:4321` in `beforeAll()` and hits it with real HTTP requests
  (`tests/helpers/HttpClient.bx`, a thin `java.net.http.HttpClient`
  wrapper), exercising `Request`/`Response`/`BodyParsers`/`StaticFiles`/
  `render()` together, plus a concurrency check using `bx:thread`.
- `tests/specs/BifsSpec.bx` — the same idea, but built entirely through the
  global BIFs (`boxExpress()`, `boxExpressJSON()`, etc.) on `localhost:4322`,
  to make sure the friendly entry points actually work, not just the
  underlying classes.
- `tests/specs/UndertowAdapterSpec.bx` — dedicated regression coverage for
  BoxExpress's Undertow wiring specifically (`models/adapters/`), including
  the real bugs found while building it: reading a request body from
  Undertow's own I/O thread, and confirming each request actually runs on
  its own virtual thread rather than Undertow's default worker pool.
- `tests/specs/ProcessLifecycleSpec.bx` — the handful of behaviors that need
  a real OS process boundary rather than an in-process `block: false`
  server: a clean exit (not a raw stack trace) on a port conflict, a real
  `SIGTERM` releasing the socket, and `reloadOnChange` actually replacing
  the running process. Spawns `boxlang` as a real subprocess against a
  generated script and asserts on its stdout/exit code.
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
