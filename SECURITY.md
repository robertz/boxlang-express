# Security policy

## Supported versions

Security fixes go into the latest release. BoxExpress is pre-1.0, so older
versions aren't patched — upgrade to the newest `0.2.x`.

## Reporting a vulnerability

Please don't open a public issue for a security problem. Report it
privately through GitHub:
[Report a vulnerability](https://github.com/robertz/boxlang-express/security/advisories/new).

Include what you can of:

- the BoxExpress and BoxLang versions
- the affected middleware or setting
- a minimal app or request that shows the problem
- what an attacker gains from it

You'll get an acknowledgement within a few days. Once a fix is released,
the changelog entry credits the reporter unless you ask otherwise.

## Scope

In scope: anything in this repository — the framework, its bundled
middleware (sessions, CSRF, CORS, helmet, rate limiting, uploads, static
files), WebSocket/STOMP support and clustering.

Out of scope: vulnerabilities in BoxLang itself or in Undertow, which
should go to those projects, and problems that need a setting the docs
describe as unsafe (for example `app.set( "trust proxy", true )` on an app
reachable without a proxy).

## Deploying safely

The protections that are off until you turn them on are listed in
[docs/limits.md](docs/limits.md) (`maxBodySize`, `requestTimeoutMs`),
[docs/helmet.md](docs/helmet.md), [docs/csrf.md](docs/csrf.md) and
[docs/rate-limiting.md](docs/rate-limiting.md). BoxExpress doesn't
terminate TLS; run it behind a reverse proxy that does, and set
`"trust proxy"` to match it.
