# Contributing to BoxExpress

This is a young, single-maintainer project — this doc exists so the next
contributor (including future-you) doesn't have to re-derive conventions
from scratch. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first if
you haven't; it explains *why* things are built the way they are. This doc
is the *how* for making a change.

## Branch & merge workflow

While this stays a single-maintainer project, direct merges (feature
branch → `develop` → `main`, no PR) are fine — that's the current
practice and there's no need to add PR ceremony just for its own sake.
**The moment a second developer starts contributing, PRs become
required** for anything landing on `develop`/`main`, so the review step
in "Before you open a PR" above actually gates merges instead of being
opt-in. Revisit this section when that happens.

## Setup

```bash
boxlang setup-tests.bxs   # once per checkout — creates the boxlang_modules symlink
boxlang run-tests.bxs     # every time after that
```

`setup-tests.bxs` has to run first and separately — module discovery
happens once at BoxLang process startup, so a symlink created mid-script
isn't picked up by that same running process.

If you're touching `java-src/boxexpress/ws/` (the compiled WebSocket
shim), rebuild the jar after editing:

```bash
java-src/build.sh
```

## Before you open a PR

- **Run the full suite** (`boxlang run-tests.bxs`) and confirm it's
  green. There's no CI gate that blocks merge on this yet (see
  ARCHITECTURE.md's "Honest open risks") — that makes running it
  yourself the only thing standing between a regression and `main`.
- **Verify empirically, not just by reasoning.** This codebase's history
  is full of "obviously correct" code that wasn't — a thread-safety fix,
  a virtual-thread dispatch bug, a disconnect-detection claim. If you're
  asserting something is thread-safe, has no injection point, or behaves
  a certain way under concurrency, prove it with a throwaway script or a
  real test before writing the permanent spec. See ARCHITECTURE.md's
  "Testing philosophy."
- **Ask for a security review on anything touching untrusted input** —
  request headers/bodies, WebSocket/STOMP frames, file paths, anything a
  client controls. Two real vulnerabilities (SSE newline injection, a
  STOMP subscription-hijack) have shipped in merged code and were only
  caught because someone asked for a review afterward. Don't assume
  someone else will ask.

## Code conventions

- **Tabs for indentation**, matching every existing file.
- **Never name a variable after a BoxLang/CFML scope** — `url`,
  `form`, `cookie`, `static`, etc. are reserved scope names; the engine
  confuses the variable with the scope. Use `userURL`, `formData`,
  `cookieHeader` instead.
- **Comments explain *why*, not *what*.** Identifiers should make the
  *what* obvious. A comment earns its place only when it captures a
  non-obvious constraint, a workaround for a specific engine bug, or a
  reason a naive implementation would be wrong.
- **Mirror the proven npm package's shape, don't invent a new one.**
  Every model that has an Express/Node/library equivalent
  (`Request`/`Response`/`Router`, `SseEmitter`, `WebSocketConnection`,
  the STOMP broker) deliberately matches that ecosystem's API shape —
  adapted to this project's factory-function-plus-options-struct
  convention, not the class-you-extend-and-override style some of those
  libraries use. If you're adding something with a well-known JS/Node
  analog, start from how that analog does it.
- **Left/right slicing on possibly-empty strings**: BoxLang's `left()`/
  `right()` throw `"Count cannot be zero"` on a zero-length slice — a
  real bug that has recurred more than once in this codebase (query
  string parsing, cookie parsing, the STOMP frame codec). Use `mid()`
  instead wherever a computed length could legitimately be zero.
- **Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)'s "BoxLang
  landmines" section before writing anything non-trivial** — it documents
  real, reproduced engine bugs/gotchas (private methods unreachable from
  a closure invoked externally, `duplicate()` failing on structs holding
  Java object references, `ExecutorService.submit()` silently swallowing
  exceptions, and more) that will otherwise cost you an hour rediscovering
  them.

## Where things live

- `models/` — core classes (`BoxExpress`, `Request`, `Response`,
  `Router`, `SseEmitter`, `WebSocketConnection`, ...).
- `models/adapters/` — the engine-neutral boundary around Undertow.
  `UndertowExchangeAdapter.bx` is the pattern to follow if you need new
  exchange-level access: read the raw Java object once into plain
  BoxLang values/methods, so the rest of the codebase never touches
  Undertow's API directly. See ARCHITECTURE.md's "exchange-adapter
  pattern" section.
- `models/middleware/` — built-in middleware (`Cors`, `Csrf`, `Helmet`,
  `Session`, `Stomp`, ...). Each one is a plain class with an `init()`,
  taking an `options` struct.
- `bifs/` — one thin BIF wrapper per public factory
  (`boxExpressJSON.bx`, `boxExpressStomp.bx`, ...), each just
  constructing and returning the matching model/middleware class. If you
  add a new middleware or model meant to be publicly constructible, add
  the matching BIF here too — that's what makes `boxExpressFoo()` work
  without a `new`/namespace.
- `java-src/boxexpress/ws/` — the one piece of compiled Java, and it
  should stay that way. Only reach for compiled Java again when BoxLang
  genuinely cannot express something (an abstract Java class with
  `protected` hooks, as with Undertow's `AbstractReceiveListener`) —
  never as a shortcut around a BoxLang quirk that has a pure-BoxLang
  workaround. See ARCHITECTURE.md's "one piece of compiled Java" section
  for the full reasoning.
- `tests/specs/` — one spec file per feature area, TestBox-based. Add
  a fixture under `tests/fixtures/` or a shared helper under
  `tests/helpers/` when a spec needs a small dedicated test double
  (see `tests/helpers/WebSocketTestClient.bx` for the shape of a raw
  hand-rolled protocol client, if you're testing something with no
  convenient BoxLang-side test client).

## Adding a new built-in middleware

A useful template, since it's the most common kind of contribution:

1. `models/middleware/YourThing.bx` — a plain class, `init(options = {})`
   storing whatever it needs into `variables`, exposing whatever
   `use()`-compatible function(s) or a `handler()` the app wires in.
2. `bifs/boxExpressYourThing.bx` — thin wrapper returning
   `new bxModules.boxexpress.models.middleware.YourThing( options )`.
3. `tests/specs/YourThingSpec.bx` — real HTTP requests against a live
   server (`app.listen(... block: false)`), not mocks, unless the thing
   under test never touches a real exchange (see `RouterSpec.bx` for
   the one exception).
4. A new section in **README.md** — usage example, options, and any
   deliberate non-goals/scope limits, same shape as the existing
   Middleware sections.
5. A **changelog entry** in README.md under the next unreleased version
   heading, plus a matching bump to `ModuleConfig.bx`'s `this.version`
   (see "Versioning" below). Explain the *why*, not just the *what* —
   the changelog is the primary record of decision rationale in this
   codebase.

## Versioning

`ModuleConfig.bx`'s `this.version` tracks the **next unreleased**
version — bump it as part of the same PR that adds the feature/fix it
describes, alongside the changelog entry. `box.json`'s version only
changes via `box bump`/`box forgebox publish`, run separately when an
actual release happens — the two are allowed to be out of sync in
between. Double-check both files before merging if it's been a while
since the last release; they've drifted out of sync before.

## Commit / PR conventions

- Commit messages and PR descriptions should explain *why* a change was
  made, not just restate the diff — same standard as in-code comments.
- Squash-worthy exploratory commits are fine locally; a clean,
  explanatory history matters more once something lands on `develop`/
  `main`.
- End commits with:
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  ```
- End PR descriptions with:
  ```
  🤖 Generated with [Claude Code](https://claude.com/claude-code)
  ```
