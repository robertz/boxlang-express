# Request

`req.method`, `req.path`, `req.originalUrl`, `req.query`, `req.params`,
`req.headers`, `req.get(name)`, `req.cookies`, `req.signedCookies` (see
[Signed cookies](response.md#signed-cookies)), `req.ip`, `req.protocol`,
`req.secure`, `req.hostname`, `req.subdomains`, `req.xhr`, `req.baseUrl`,
`req.setTimeout(ms)` / `req.timedOut` (see [Timeouts and size
limits](limits.md)),
`req.accepts(...)`, `req.acceptsLanguages(...)`, `req.is(...)` (see
[Content negotiation](#content-negotiation) and [More request
properties](#more-request-properties) below), `req.body` (populated by body-parsing
middleware — a struct, or a string or `byte[]` from the text and raw
parsers — empty struct otherwise), `req.rawExchange()` (an escape hatch to
the underlying `io.undertow.server.HttpServerExchange`, for anything not
covered by the rest of the API).

Cookie and query values are URL-decoded. One with a malformed escape
(`discount=50%`, `%zz`) is kept as its raw text rather than failing the
request — a cookie can be set by any script or sibling subdomain, so a bad
one shouldn't lock a visitor out.

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

## Content negotiation

`req.accepts( types )` returns whichever of the types you offer the
request's `Accept` header prefers, or `false` if it accepts none of them.
Offer extensions or full MIME types, as separate arguments or one array;
you get back the same string you passed:

```js
app.get( "/report", ( req, res ) => {
	switch ( req.accepts( "html", "json", "csv" ) ) {
		case "json": return res.json( report )
		case "csv": return res.attachment( "report.csv" ).send( toCsv( report ) )
		case "html": return res.render( "report", report )
		default: return res.sendStatus( 406 )
	}
} )
```

The client's `q` weights decide, then how specific its range is
(`text/html` over `text/*` over `*/*`), then the order you offered them
in. A request with no `Accept` header accepts anything, so it gets your
first offer. Called with no arguments, `req.accepts()` returns the types
the client listed, most preferred first. For the common "one handler per
type" case, [`res.format()`](response.md#resformat) does the switch for
you.

`req.acceptsLanguages( "fr", "en" )` is the same against
`Accept-Language`. `en` satisfies a client asking for `en-US`, and `en-GB`
satisfies one asking for `en`; an exact tag wins over either.

`req.is( types )` checks the request's own `Content-Type` — what the
client *sent*, not what it wants back:

```js
app.post( "/hook", ( req, res ) => {
	if ( !req.is( "json", "+json" ) ) {
		return res.sendStatus( 415 )
	}
	...
} )
```

It returns the first type that matches, or `false`. A type can be an
extension (`"json"`), a full type, a wildcard (`"text/*"`, `"*/xml"`), a
suffix (`"+json"`, matching `application/vnd.api+json`), or the shorthands
`"urlencoded"` and `"multipart"`. A wildcard or suffix match returns the
actual type. A request with no `Content-Type` is always `false` — Express
returns `null` for a request with no body; here it's `false` either way.

Two limits: media-type parameters other than `q` are ignored
(`text/html;level=1` is treated as `text/html`), and there's no
`req.acceptsEncodings()`/`req.acceptsCharsets()`, since BoxExpress doesn't
compress or transcode responses.

## More request properties

- `req.xhr` — `true` when `X-Requested-With` is `XMLHttpRequest`, which
  jQuery and axios send and `fetch()` doesn't.
- `req.subdomains` — the labels in front of the domain, nearest the domain
  first: `tobi.ferrets.example.com` gives `[ "ferrets", "tobi" ]`. The last
  two labels are taken to be the domain; for `example.co.uk` set
  `app.set( "subdomain offset", 3 )`. Empty for an IP address. It's built
  from `req.hostname`, so it follows `trust proxy` the same way.
- `req.baseUrl` — the path the current router or path-scoped middleware is
  mounted at, `""` at the top level. Inside a router mounted with
  `app.use( "/api", apiRouter )`, a request for `/api/users/42` has
  `req.baseUrl` `"/api"`, `req.path` `"/users/42"` and `req.originalUrl`
  `"/api/users/42"`. Nested mounts add up (`/api/v2`), and a param in the
  mount path shows its value, not `:name`. Use it to build links that keep
  working wherever the router is mounted:

  ```js
  apiRouter.post( "/users", ( req, res ) => {
  	var user = users.create( req.body )
  	res.status( 201 ).location( req.baseUrl & "/users/" & user.id ).json( user )
  } )
  ```
