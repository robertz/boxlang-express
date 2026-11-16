# Response

`res.status(code)`, `res.set(name,val)` / `res.header(...)`,
`res.set({...})`, `res.get(name)`, `res.append(name,val)`, `res.type(mime)`,
`res.vary(field)`, `res.location(url)`, `res.links({...})`,
`res.attachment(filename)`, `res.format({...})`, `res.clearCookie(name,options)`
(see [Header helpers](#header-helpers), [`res.format()`](#resformat) and
[Cookies](#cookies) below),
`res.send(data)`, `res.json(data)`, `res.sendStatus(code)`,
`res.redirect(url, code=302)`, `res.cookie(name,val,options)`,
`res.sendBytes(bytes, contentType)`, `res.sendFile(path, options)`,
`res.download(path, filename)`, `res.end(data)`, `res.render(view, data)`,
`res.dump(data)` (sends BoxLang's rich, collapsible `dump()` HTML view of a
variable as the response — a quick debugging escape hatch; see the note
below), `res.getStatusCode()`/`res.getBytesWritten()` (the eventual status
code and body byte count, for request-logging middleware that can't
otherwise observe a request's outcome), `res.onBeforeSend(callback)`
(registers a callback that runs once, synchronously, the instant before
headers are actually flushed — the one point guaranteed to run after every
downstream middleware/handler but still early enough to add a header; the
session middleware's cookie-vs-`saveUninitialized` decision is what this
was built for), `res.sse(callback)` (Server-Sent Events — see
[Server-Sent Events](sse.md) for the full shape).

Calling a second terminal method (`send`/`json`/`redirect`/`end`/`sendBytes`/
`sendFile`/`download`/`render`/`dump`/`sse`) on the same response throws —
mirrors Express's "headers already sent".

`res.dump(data)` only forwards `data` to BoxLang's `dump()` BIF — none of
its other options (`label`, `expand`, `top`, etc.). It round-trips the HTML
through a temp file, since `dump(format="html")` only ever writes to the
process console under BoxExpress's CLI-based `HttpServer`; that round trip,
plus the dump renderer itself, cost more than `res.json()`, so it's meant
for an occasional debug route, not a hot path. Reach for the same temp-file
pattern directly (see [Response.bx](../models/Response.bx)) if you need those
extra options.

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
works. A request with `Cache-Control: no-cache` (a hard reload) always
gets the file. The `ETag` is a cheap weak tag (`W/"<size>-<mtime>"`, no file hash),
so it changes whenever the file's size or modified time changes on disk.

Both also accept `options.maxAge` (seconds) to set `Cache-Control:
public, max-age=<n>`, letting a browser skip revalidation entirely for that
long instead of asking on every request — off by default (no header at
all) since caching a response the app didn't ask to be cached is the wrong
default:

```js
app.use( "/public", boxExpressStatic( expandPath( "./public" ), { maxAge: 86400 } ) )  // 1 day

app.get( "/report", ( req, res ) => {
	res.sendFile( expandPath( "./reports/latest.pdf" ), { maxAge: 3600 } )  // 1 hour
} )
```

## ETags and conditional requests

`res.send()`, `res.json()`, `res.sendBytes()`, `res.render()` and
`res.end()` can tag what they send, so a client that already has the
current copy gets an empty `304 Not Modified` instead of the body again.
It's off by default; turn it on with one setting:

```js
app.set( "etag", "weak" )
```

| `app.set( "etag", ... )` | `ETag` header |
|---|---|
| `false` (default) | none |
| `"weak"` or `true` | `W/"b-Kq5sNclPz7QV2+lfQIuc6R7oRu0"` — body length in hex, then a SHA-1 of the body. Express's default. |
| `"strong"` | the same without `W/` |
| `( bodyBytes, encoding ) => ...` | whatever the function returns, quotes included; nothing if it returns `""` |

Any other value throws at `app.set()`. `encoding` is `"utf-8"` for a body
built from a string and `""` for `res.sendBytes()`.

A response is tagged only when the request is a `GET` or `HEAD`, the
status is 2xx, the body isn't empty, and the handler hasn't set an `ETag`
of its own.

**The 304.** Whether or not `etag` is on, a `GET` or `HEAD` response with
a 2xx status is checked against the request before it's sent, using
whatever `ETag` and `Last-Modified` the response carries — generated, or
set by your handler. The rules are the `fresh` package's, as in Express 5:

- `If-None-Match` matches when it's `*` or lists the response's `ETag`.
  Weak and strong forms of the same tag match each other; the comparison
  is case-sensitive.
- Without `If-None-Match`, `If-Modified-Since` matches when the
  response's `Last-Modified` is no later.
- `Cache-Control: no-cache` on the request (a browser's hard reload)
  always gets the full response.

A match is sent as `304` with no body and without `Content-Type` or
`Content-Length`; every other header (`ETag`, `Cache-Control`, `Vary`,
cookies) is kept. `res.getStatusCode()` then reports `304` and
`res.getBytesWritten()` `0`, so access logs show it accurately.

The handler still runs in full — the tag is a hash of the body it built,
so this saves bandwidth, not work. To skip the work, set a validator you
can compute cheaply and check `req.fresh` before doing anything
expensive:

```js
app.get( "/reports/:id", ( req, res ) => {
	var version = reports.versionOf( req.params.id )   // cheap
	res.set( "ETag", '"report-#version#"' )
	if ( req.fresh ) {
		return res.status( 304 ).end()
	}
	res.json( reports.build( req.params.id ) )         // expensive
} )
```

`req.fresh` and `req.stale` are read like properties and recomputed on
each read, from the validators set on the response at that moment — so
read them after setting `ETag` or `Last-Modified`. They are always
`false`/`true` for anything other than a `GET` or `HEAD`.

`sendFile()`, static files and `res.sse()` don't use the `etag` setting:
files keep the size-and-mtime tag described above, and a stream has no
body to hash.

## Range requests (partial content)

Both `sendFile()`/`download()` and static file serving honor a `Range`
request header (RFC 7233) and respond `206 Partial Content` with just the
requested slice — what makes video/audio scrubbing and resumable downloads
work, since the client doesn't have to (re-)download the whole file to seek
or resume. Every file response sets `Accept-Ranges: bytes`, even a full
`200`, so a client knows it can send a `Range` request on a later one:

```js
app.get( "/video", ( req, res ) => {
	res.sendFile( expandPath( "./media/clip.mp4" ) )
} )
// curl -H "Range: bytes=0-1023" localhost:3000/video
// -> 206, Content-Range: bytes 0-1023/<total>, body is just those 1024 bytes
```

A range naming a start beyond the file's size gets a `416 Range Not
Satisfiable` with `Content-Range: bytes */<total>` and no body, matching the
spec. Two things are deliberately out of scope, the same trade-off most
minimal static-file servers make: a request naming more than one range
(`bytes=0-10,20-30`) falls back to a full `200` response rather than a
`multipart/byteranges` reply, and `If-Range` (a conditional range against a
validator) isn't supported — a `Range` request is always attempted
regardless of freshness. See [RangeParser.bx](../models/RangeParser.bx).

## Header helpers

```js
res.set( { "X-Frame-Options": "DENY", "X-Robots-Tag": "noindex" } )  // several at once
res.get( "Content-Type" )                 // what's been set so far, or null
res.append( "Warning", "199 Miscellaneous warning" )   // adds; set() replaces
res.type( "json" )                        // an extension works: "json", ".png", "csv"
res.vary( "Accept-Language" )
res.location( "/users/42" )               // just the header — no status, response stays open
res.links( { next: "/users?page=3", last: "/users?page=9" } )
res.attachment( "report.csv" )
```

- **`res.append()`** writes `Set-Cookie` as one header line per value.
  Any other header is joined into a single comma-separated line — the same
  thing to a client, and it keeps `res.get()` returning the whole value.
- **`res.type()`** with no `/` in it is looked up as a file extension; an
  unknown one becomes `application/octet-stream`.
- **`res.vary()`** adds to `Vary` rather than replacing it and skips a
  field that's already listed, so middleware can each add their own. Set
  it whenever a response depends on a request header (`Accept`,
  `Accept-Language`, `Origin`, `Cookie`) and may be cached — otherwise a
  shared cache can hand one visitor a response built for another.
  `boxExpressCors()` and `res.format()` call it for you. `"*"` replaces
  the list and stays.
- **`res.links()`** builds a `Link` header (`</users?page=3>; rel="next"`),
  the usual way to page an API, and adds to one already set.
- **`res.attachment()`** sets `Content-Disposition: attachment` for a body
  you build yourself, where [`res.download()`](#response) is for a file on
  disk. A filename also sets `Content-Type` from its extension.

A value with a line break in it is refused (the handler gets a
`BoxExpress.InvalidHeaderValue`), same as `res.set()`.

## `res.format()`

Runs the handler for whichever type the request's `Accept` header prefers
— one route serving a browser and an API client:

```js
app.get( "/users/:id", ( req, res ) => {
	var user = users.get( req.params.id )
	res.format( [
		html: () => res.render( "user", user ),
		json: () => res.json( user ),
		"text/csv": () => res.attachment( "user.csv" ).send( toCsv( user ) )
	] )
} )
```

Keys are extensions or full MIME types. `Content-Type` is set to the
chosen one before its handler runs, and `Accept` is added to `Vary`. If
nothing matches, a `default` handler runs if you gave one; otherwise the
response is `406 Not Acceptable`.

**Use an ordered struct (`[ ... ]`), as above.** When a client has no
preference (`Accept: */*`, or no header — `curl`, most API clients) the
first key wins, and a plain `{ ... }` struct doesn't keep its keys in the
order you wrote them. Selection follows the same rules as
[`req.accepts()`](request.md#content-negotiation).

## Cookies

`res.cookie( name, value, options )` options: `path` (default `"/"`),
`domain`, `maxAge` (seconds), `expires` (a date), `httpOnly` (default
`true`), `secure`, `sameSite`, `signed`.

`res.clearCookie( name, options )` tells the browser to drop a cookie, by
sending it back empty and already expired:

```js
res.cookie( "prefs", "dark", { path: "/account", domain: "example.com" } )
// later
res.clearCookie( "prefs", { path: "/account", domain: "example.com" } )
```

A browser only removes the cookie whose `path` and `domain` match, so pass
the same ones it was set with — a cookie set with `path: "/account"` is
untouched by a bare `res.clearCookie( "prefs" )`. Pass `secure` and
`sameSite` along too if it was set with `sameSite: "None"`, which browsers
reject without `Secure`. To end a session, use `req.destroySession()`
instead (see [Sessions](sessions.md)), which also removes the server-side
data.

### Signed cookies

A cookie is whatever the browser sends back, and a user can edit theirs.
Signing one lets you tell: the value travels with an HMAC of itself, and a
value that doesn't match its signature is rejected when it comes back.

```js
app.set( "cookie secret", server.system.environment.COOKIE_SECRET )

app.post( "/prefs", ( req, res ) => {
	res.cookie( "plan", "pro", { signed: true, maxAge: 86400 } )
	res.sendStatus( 204 )
} )

app.get( "/dashboard", ( req, res ) => {
	var plan = req.signedCookies.plan ?: "free"
	if ( plan === false ) {
		plan = "free"            // present, but tampered with
	}
	...
} )
```

- **`req.signedCookies`** holds each signed cookie's original value. A
  cookie whose signature doesn't check out is there as `false`, so you can
  tell "tampered" from "absent". Test for it with `=== false`: a plain
  `==` would also match a genuine value of `"false"`, `"no"` or `"0"`.
  Signed cookies are removed from
  `req.cookies` either way — a forged one can't be picked up by code
  reading the plain struct.
- **`res.cookie( ..., { signed: true } )`** throws
  `BoxExpress.NoCookieSecret` if no secret is set.
- **Signed is not encrypted.** The value is readable by anyone holding
  the cookie; the signature only proves the server issued it. Don't put
  anything secret in one. A signed cookie is also valid for as long as
  the browser keeps it — signing doesn't add an expiry or tie the value
  to one user — so for anything that must be revocable, keep it in the
  [session](sessions.md) instead.
- **Keep the secret out of source control** and make it long and random
  (32+ bytes). An empty secret throws at `app.set()`.
- **Rotating the secret:** pass an array. The first entry signs new
  cookies and every entry is accepted when reading, so existing cookies
  keep working until you drop the old one:

  ```js
  app.set( "cookie secret", [ newSecret, oldSecret ] )
  ```

The format is the one Express's `cookie-parser` uses
(`s:<value>.<base64 HMAC-SHA256>`), so an Express app and a BoxExpress app
sharing a secret can read each other's signed cookies. Values are signed
as strings; there's no equivalent of cookie-parser's `j:` JSON cookies —
serialize a struct yourself if you need one. The session cookie isn't
signed and doesn't need to be: it's a random 256-bit id with nothing to
forge.
