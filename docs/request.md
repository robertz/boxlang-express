# Request

`req.method`, `req.path`, `req.originalUrl`, `req.query`, `req.params`,
`req.headers`, `req.get(name)`, `req.cookies`, `req.ip`, `req.protocol`,
`req.secure`, `req.hostname`, `req.body` (populated by body-parsing
middleware, empty struct otherwise), `req.rawExchange()` (an escape hatch to
the underlying `io.undertow.server.HttpServerExchange`, for anything not
covered by the rest of the API).

`req.ip` is always the direct TCP peer by default — safe, since a client
can't spoof it, but wrong behind a reverse proxy (it'll report the proxy's
IP). `app.set( "trust proxy", ... )` says which proxies to believe, with the
same meanings as Express:

| Value | `req.ip` is |
|---|---|
| `false` (default) | the TCP peer |
| a number, e.g. `1` | the address the nearest *n* proxies saw — counted from the right of `X-Forwarded-For`, where each proxy appends. `1` fits a single load balancer. |
| addresses: `[ "10.0.0.0/8", "loopback" ]` | walks `X-Forwarded-For` from the right past each trusted address (IPs, CIDR ranges, `loopback`, `linklocal`, `uniquelocal`) and takes the first one that isn't |
| `true` | the left-most `X-Forwarded-For` entry |

Prefer a hop count or an address list. With `true`, the left-most entry
is whatever the client put there when its proxy appends rather than
replaces the header (most do), so a client can choose its own `req.ip` —
and with it, a fresh rate-limit key per request. An invalid entry fails at
`app.set()`, and hostnames in the header are never looked up in DNS.

`req.protocol`/`req.secure`/`req.hostname` follow the same trust model.
BoxExpress's own `HttpServer` never terminates TLS itself, so `req.protocol`
is always `"http"` (`req.secure` always `false`) unless the directly
connected peer is a trusted proxy *and* the request carries
`X-Forwarded-Proto: https` — the shape you'd see behind a TLS-terminating
reverse proxy. `req.hostname` is the `Host` header (or, from a trusted
proxy, `X-Forwarded-Host` if present) with any `:port`
suffix stripped — an IPv6 host (`[::1]:3000`) is left bracketed rather than
mangled at the first colon.
