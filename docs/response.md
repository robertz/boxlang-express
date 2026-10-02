# Response

`res.status(code)`, `res.set(name,val)` / `res.header(...)`, `res.type(mime)`,
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
works. The `ETag` is a cheap weak tag (`W/"<size>-<mtime>"`, no file hash),
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
