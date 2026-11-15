# Timeouts and size limits

Five settings bound what one client can cost the server. Set them with
`app.set()` before `listen()`; `0` turns any of them off.

| Setting | Default | What it bounds |
|---|---|---|
| `"requestTimeoutMs"` | `0` (off) | How long a handler has to start its response |
| `"maxBodySize"` | `0` (off) | Request body size, in bytes, for the whole app |
| `"headerTimeoutMs"` | `60000` | How long a client has to finish sending its request headers |
| `"keepAliveTimeoutMs"` | `60000` | How long an idle keep-alive connection is held open |
| `"maxHeaderSize"` | `65536` | Total size of the request line and headers, in bytes |

```js
app.set( "requestTimeoutMs", 30000 )   // 30s
app.set( "maxBodySize", 10485760 )     // 10MB
```

A value that isn't a whole number, 0 or more, throws
`BoxExpress.InvalidArgument` at the `app.set()` call.

## Request timeout

Without one, a handler waiting on something that never answers — a
database lock, an upstream API — holds its connection until the client
gives up, and nothing tells you it happened. With `requestTimeoutMs` set,
a request that hasn't started its response in time is answered with:

```
HTTP/1.1 503 Service Unavailable
Connection: close

{"error":true,"message":"Request timed out"}
```

and a line is logged (`GET /report timed out after 30000ms`), even with
`app.set( "log", false )`.

**How it works, and what follows from it.** When the time is up the
handler's thread is interrupted, so whatever blocking call it's in —
`sleep()`, a query, an HTTP call, reading the request body — throws. That
unwinds the handler, and the `503` is sent from the handler's own thread.

- **Error-handling middleware doesn't run** for a timeout. The exception
  is only how the interruption surfaced, not an application error, so the
  `503` is answered directly, like a body parser's `413`.
- **A handler that catches the exception keeps control.** It can clean up
  and send its own response; `req.timedOut` is `true` so it can tell a
  timeout from a real failure:

  ```js
  app.get( "/report", ( req, res ) => {
  	try {
  		res.json( buildReport() )
  	} catch ( any e ) {
  		if ( req.timedOut ) {
  			return res.status( 503 ).json( { error: true, message: "Report is taking too long; try a smaller date range." } )
  		}
  		rethrow;
  	}
  } )
  ```

- **A handler that never blocks isn't stopped.** A pure CPU loop has
  nothing to interrupt. It gets the `503` only if it finishes without
  responding.
- **A response that has started is left alone.** The timeout covers the
  time *to start responding*: an SSE stream, a large `res.sendFile()` or
  a `res.download()` can run as long as it needs. WebSocket connections
  aren't affected at all.
- **A slow upload counts.** A handler (or body parser) waiting on a
  request body that trickles in is interrupted like any other.
- Deadlines are checked about every 50 ms, so a timeout fires up to that
  much late.

### One route that needs longer: `req.setTimeout()`

`req.setTimeout( ms )` replaces the app's timeout for the current request,
counted from the moment you call it. `0` turns it off:

```js
app.get( "/export", ( req, res ) => {
	req.setTimeout( 120000 )   // this one legitimately takes a while
	res.json( buildExport() )
} )

app.post( "/import", ( req, res, next ) => { req.setTimeout( 0 ); next() }, importHandler )
```

It works whether or not `requestTimeoutMs` is set, so it can also put a
timeout on a single route in an app that has none.

## Body size

`maxBodySize` is a cap for the whole app, checked before any middleware or
handler runs. A request over it gets:

```
HTTP/1.1 413 Request Entity Too Large
Connection: close

{"error":true,"message":"Request body exceeds the 10485760-byte limit"}
```

The body parsers (`boxExpressJSON()`, `boxExpressUrlencoded()`,
`boxExpressText()`, `boxExpressRaw()`) and `boxExpressUpload()`
each have their own `limit` (see [Middleware](middleware.md) and [File
uploads](uploads.md)), but those only apply to the routes they're mounted
on and the content types they parse. `maxBodySize` covers the rest: a
route with no parser, a handler reading `req.bodyStream()` itself. Set it
at or above your largest parser limit — the smaller of the two wins.

A request that declares its size (`Content-Length`) is refused on the
header alone, before any of the body is read, and the connection is
closed rather than waiting for the rest. A chunked request declares no
size, so the only way to enforce the cap is to read it: with `maxBodySize`
set, a chunked body is read into memory up to the cap before the handler
runs, and `req.bodyStream()` then reads from that copy. If you stream
large chunked uploads to disk through `req.bodyStream()`, leave
`maxBodySize` off and bound them yourself.

It's off by default so that upgrading can't start rejecting uploads an
app already accepts.

## Connection limits

These three are enforced by Undertow before a request reaches BoxExpress,
so there's no `req`, no log line and no middleware involved.

- **`headerTimeoutMs`** — a client that connects and sends its headers a
  byte at a time, or not at all, is disconnected. Before 0.2.27 there was
  no limit, so such a connection was held open indefinitely.
- **`keepAliveTimeoutMs`** — how long a connection may sit idle between
  requests. This only counts time with no request in progress; a slow
  handler or a long-lived stream isn't idle. Keep it *longer* than your
  reverse proxy's upstream keep-alive timeout, or the proxy will
  occasionally reuse a connection the server has just closed and report a
  `502`.
- **`maxHeaderSize`** — a request whose headers exceed it gets a bare
  `400 Bad Request`. 64 KB is far more than a browser sends (large
  cookies are a few KB) and most proxies cap lower still; raise it if you
  pass large tokens in headers.

A reverse proxy in front usually enforces its own versions of all three,
which is one more reason to have one — but these still bound what the
proxy itself, or anything else that can reach the port, can do.

## Not covered

- **A client that reads its response slowly** isn't timed out. A proxy
  that buffers responses (nginx does by default) absorbs this.
- **Total connections** aren't capped; each costs a virtual thread only
  while a request is running.
- **WebSocket and STOMP** have their own limits — `wsMaxMessageSize`,
  `wsIdleTimeoutMs`; see [WebSockets](websockets.md).
- For limiting how *often* a client may call you, see [Rate
  limiting](rate-limiting.md).
