# Server-Sent Events (`res.sse`)

`res.sse(callback)` opens a long-lived, one-way event stream to the
client — the callback receives an `emitter` and writes to it as events
happen, instead of building one response body up front:

```js
app.get( "/events", ( req, res ) => {
	res.sse( ( emitter ) => {
		emitter.send( "hello" )
		emitter.send( { status: "processing", progress: 50 }, "update" )
		emitter.send( { complete: true }, "done" )
		emitter.close()
	} )
} )
```

`emitter.send(data, event, id)` writes one SSE event — `data` is
JSON-serialized unless it's already a simple value, `event`/`id` are
optional. `data` is split per-line so a multi-line payload can't break out
of its own field; `event`/`id` are single-line fields, so any `char(13)`/
`char(10)` in them is stripped before writing, since either one is
commonly a candidate for request-derived input (a channel name, a
correlation ID) — without stripping, an embedded newline there would let
a client inject a fabricated event into the stream every other client
reading it sees. `emitter.comment(text)` sends an SSE comment line
(invisible to the client, useful as a keep-alive through a proxy that
times out idle connections; same newline-stripping applies). `emitter.close()` ends the stream; `res.sse()` also closes
it in a `finally`, so a callback that throws or returns without calling
`close()` itself still ends the stream cleanly rather than leaking an open
connection. `emitter.isClosed()` goes `true` the moment a write fails
(the client disconnected) — check it in a loop to stop work early rather
than continuing to compute updates nobody's listening for:

```js
app.get( "/dashboard/stream", ( req, res ) => {
	res.sse( ( emitter ) => {
		while ( !emitter.isClosed() ) {
			emitter.send( { activeUsers: getActiveUserCount() }, "metrics" )
			sleep( 1000 )
		}
	} )
} )
```

Sets `Content-Type: text/event-stream`, `Cache-Control: no-cache`,
`Connection: keep-alive`, and `X-Accel-Buffering: no` — that last one
stops a reverse proxy (nginx, Cloudflare) from buffering the stream and
defeating the whole point of it.

The `emitter` is a plain object — nothing ties it to being used only
inside the callback it was handed to. Stash it somewhere shared (a
module-level struct keyed by connection ID, say) and another route can
call `.send()` on it directly, for a broadcast/fan-out pattern:

```js
subscribers = {}   // shared across requests — id -> emitter

app.get( "/events", ( req, res ) => {
	res.sse( ( emitter ) => {
		var id = createUUID()
		subscribers[ id ] = emitter
		try {
			while ( !emitter.isClosed() ) {
				sleep( 500 )   // sends happen from /broadcast below
			}
		} finally {
			structDelete( subscribers, id )
		}
	} )
} )

app.post( "/broadcast", ( req, res ) => {
	for ( var id in subscribers ) {
		subscribers[ id ].send( req.body.message, "message" )
	}
	res.json( { sentTo: structCount( subscribers ) } )
} )
```

`send()`/`comment()`/`close()` are safe to call this way from more than
one thread at once — confirmed under real concurrent load (5 threads
calling `send()` on the same emitter simultaneously, 125 total writes,
zero interleaved/corrupted frames) — each funnels through the same
per-emitter lock, so two writers can't tear a frame in half on the shared
`OutputStream`.

This isn't a wrapper around BoxLang's own `SSE()` BIF — confirmed directly
that it isn't reachable here at all (`Function [SSE] not found`), since it
lives in the `boxlang-web-support` runtime module that MiniServer/
CommandBox/servlet deployments load and BoxExpress doesn't. `res.sse()`'s
`emitter` API deliberately mirrors that BIF's shape anyway, since it's a
proven design — just implemented from scratch against Undertow's own raw
exchange (`req.rawExchange()`). See [SseEmitter.bx](../models/SseEmitter.bx).
