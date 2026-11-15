# Middleware

Handlers are `(req, res, next) => {...}`. Call `next()` to continue to the
next matching layer, or `next(err)` to jump to error-handling middleware.
Error-handling middleware is any handler with **4** parameters —
`(err, req, res, next) => {...}` — detected the same way Express does
(`fn.length === 4`), and should be registered last.

`app.use(handler)` / `app.use(path, handler)` both also take more than one
handler (or a mix of handlers and a mountable `Router`) in a single call —
`app.use(mw1, mw2, mw3)` registers three separate stack layers at once,
running in the order given, same as `app.use(mw1); app.use(mw2);
app.use(mw3)` would:

```js
app.use(
	( req, res, next ) => { println( "#req.method# #req.path#" ); next() },
	( req, res, next ) => { res.set( "X-Powered-By", "BoxExpress" ); next() }
)
```

The first argument is only ever treated as a mount path when it's a plain
string — anything else (a closure, or a `Router`) is a target, so
`app.use(handler)` and `app.use(path, handler)` are told apart the same way
regardless of how many more arguments follow.

Built-in middleware factories (opt-in, same philosophy as `express.json()`),
each with its own global BIF mirroring the Express function of the same name:

```js
app.use( boxExpressJSON() )        // parses application/json bodies into req.body
app.use( boxExpressUrlencoded() )  // parses application/x-www-form-urlencoded bodies
app.use( boxExpressText() )        // text/plain bodies: req.body is a string
app.use( boxExpressRaw() )         // application/octet-stream bodies: req.body is a byte[]
app.use( "/public", boxExpressStatic( expandPath( "./public" ) ) )
app.use( boxExpressSession() )     // cookie-based sessions, req.session
app.use( boxExpressHelmet() )      // security headers — see helmet.md
app.use( boxExpressCors() )        // Cross-Origin Resource Sharing — see cors.md
app.use( boxExpressRateLimit() )   // 100 req/min per req.ip — see rate-limiting.md
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

All four body parsers (`json()`, `urlencoded()`, `text()`, `raw()` and
their `boxExpress...()` BIFs) cap
the request body at 100KB by default, to keep a slow or malicious client from
buffering an unbounded body into memory — override with `{ limit: bytes }`:

```js
app.use( boxExpressJSON( { limit: 5000000 } ) )  // 5MB
```

These limits apply only where a parser is mounted and to the content type
it parses; `app.set( "maxBodySize", bytes )` caps every request — see
[Timeouts and size limits](limits.md).

A body that won't parse (broken JSON, a malformed `%` escape in a
urlencoded body) is answered with a 400.

A body over the limit gets a `413` response and never reaches your route
handler. A JSON body that doesn't parse gets a `400`
(`{ error: true, message: "Malformed JSON body" }`), answered directly like
the `413` rather than passed to error-handling middleware.
### Text and raw bodies

`boxExpressText()` puts the body on `req.body` as a string, and
`boxExpressRaw()` as the exact bytes received (a Java `byte[]`):

```js
app.post( "/notes", boxExpressText(), ( req, res ) => {
	notes.add( req.body )                    // a string
	res.sendStatus( 201 )
} )

app.put( "/avatars/:id", boxExpressRaw( { type: "image/*", limit: 2000000 } ), ( req, res ) => {
	fileWrite( avatarPath( req.params.id ), req.body )   // a byte[]
	res.sendStatus( 204 )
} )
```

By default `text()` parses `text/plain` and `raw()` parses
`application/octet-stream`; anything else is passed over and `req.body`
stays `{}`. `text()` decodes with the charset the `Content-Type` names,
`options.defaultCharset` (UTF-8) when it names none, and answers `415`
for a charset the JVM doesn't have. `raw()`'s bytes aren't valid input to
`res.json()` — encode them (`binaryEncode( req.body, "base64" )`) first.

### Choosing what a parser handles (`type`)

Every parser takes a `type` option: a MIME type, a file extension, a
wildcard or a `+suffix` — the same patterns as
[`req.is()`](request.md#content-negotiation) — or an array of those, or a
function `( req ) => boolean`:

```js
app.use( boxExpressJSON( { type: [ "json", "+json" ] } ) )   // also application/vnd.api+json
app.use( boxExpressText( { type: [ "text/*", "xml" ] } ) )
app.use( boxExpressRaw( { type: "*/*" } ) )                  // whatever nothing above parsed
```

A request body can only be read once, so a parser skips any request an
earlier parser (or `boxExpressUpload()`) already handled. That makes a
catch-all like the last line safe to put at the end of the list — it only
sees what the others passed over — and it means order is how you resolve
an overlap.

### Checking a signature over the raw body (`verify`)

Webhook senders (Stripe, GitHub, Slack) sign the exact bytes they send.
Re-serializing the parsed JSON doesn't reproduce those bytes, so the check
has to see the body before it's parsed. Every parser takes a `verify`
option for this — `( req, res, bodyBytes ) => {...}`, called with the
bytes as received. Throw to reject the request:

```js
app.post( "/webhooks/billing",
	boxExpressJSON( { verify: ( req, res, bodyBytes ) => {
		var expected = lCase( hmac( bodyBytes, webhookSecret, "HmacSHA256" ) )
		var given = req.get( "X-Signature" ) ?: ""
		// A constant-time comparison: == stops at the first difference,
		// which leaks how much of a guess was right.
		if ( !createObject( "java", "java.security.MessageDigest" ).isEqual( expected.getBytes( "UTF-8" ), given.getBytes( "UTF-8" ) ) ) {
			throw( "signature mismatch" )
		}
	} } ),
	( req, res ) => {
		handleEvent( req.body )   // parsed as usual, and known to be authentic
		res.sendStatus( 204 )
	}
)
```

A `verify` that throws gets the client a `403`
(`{ error: true, message: "Request body failed verification" }`) and the
route never runs; the thrown message isn't sent. `verify` can also send
its own response instead of throwing. It isn't called for a body over the
`limit`, which is refused first.

Mount the verifying parser on the webhook route itself, *before* any
app-wide `app.use( boxExpressJSON() )` — whichever parser reaches the body
first is the one that reads it, and an app-wide parser registered earlier
would parse the body with no check, leaving the route's parser nothing to
verify. Each sender has its own header name and signature format (some
prefix `sha256=`, some sign a timestamp along with the body); follow
theirs.


`boxExpressStatic()`/`StaticFiles.serve()` resolve requested files
against the real (symlink-resolved) served directory, so a symlink placed
inside it can't be used to read files from outside it.

Static files ignore dotfiles by default: a path with any segment starting
with `.` (`/.env`, `/.git/config`) falls through to the next handler as if
the file weren't there, so a stray secret in the public directory isn't
served. `{ dotfiles: "deny" }` answers `403` instead, and
`{ dotfiles: "allow" }` serves them; `/.well-known/` is always served.
Files are streamed from disk rather than read into memory, so a large file
costs the same memory as a small one, `HEAD` never reads the file, and
files over 2 GB (including `Range` requests into them) work — the same
applies to `res.sendFile()` and `res.download()`.

## Built-in middleware

- [File uploads](uploads.md) — `boxExpressUpload()`
- [Sessions](sessions.md) — `boxExpressSession()`, `boxExpressCacheStore()`
- [Security headers](helmet.md) — `boxExpressHelmet()`
- [CORS](cors.md) — `boxExpressCors()`
- [Rate limiting](rate-limiting.md) — `boxExpressRateLimit()`
- [CSRF protection](csrf.md) — `boxExpressCsrf()`
- [Request validation](validation.md) — `boxExpressValidate()`
- [Error handling](error-handling.md) — default `404`/`500` responses
