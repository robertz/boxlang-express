# WebSockets (`app.ws`)

`app.ws(path, callback)` registers a WebSocket route — separate from
`app.get/post/...` and `Router`, since a WebSocket connection has no
`req`/`res`/`next()` chain to run through. `callback` receives a
`WebSocketConnection` the moment a client connects; register what you need
on it before returning, since messages can start arriving as soon as the
handshake completes:

```js
app.ws( "/chat", ( connection ) => {
	connection.onMessage( ( text ) => {
		connection.send( "echo:" & text )
	} )
	connection.onClose( ( code, reason ) => {
		println( "client disconnected: #code#" )
	} )
} )
```

`connection.send(data)` — one text message; JSON-serialized unless
already a simple value, same convention as `res.sse()`'s `emitter.send()`.
`connection.onMessage(callback)` — `callback(text)` runs for every message
this connection receives. `connection.onClose(callback)` —
`callback(code, reason)` runs once, whether the client disconnected,
the connection dropped, or `connection.close()` was called locally (a
locally-initiated close did not fire it before 0.2.15).
`connection.onBinary(callback)` — `callback(bytes)` (a Java `byte[]`) for
binary messages, which are dropped if none is registered; outbound
messages are always text. `connection.isClosed()`.
`connection.headers`/`connection.cookies` carry the handshake request's
headers/cookies (mirroring `req.headers`/`req.cookies`) — read a session
cookie here to authenticate a connection, since `app.ws()` routes sit
outside the `use()` middleware chain entirely (no `Session`/`Cors`/`Csrf`
middleware runs for a WebSocket upgrade). `connection.get(name)` reads a
single header.

Like `res.sse()`'s `emitter`, a `connection` is a plain object — stash it
somewhere shared to push to it from a completely different route later
(a chat broadcast, a live update triggered by an unrelated `POST`).
`send()`/`close()` are safe to call from another thread than the one that
opened the connection, funneled through a per-connection lock so
concurrent writers can't interleave and corrupt a frame — same fix, same
reasoning as `SseEmitter`'s. `send()` bounds its underlying blocking
write with a hard 5s timeout — a peer that vanishes mid-write (network
drop, abrupt client close) can't hang the caller, or pin the lock out
from under any other thread also trying to send on that connection, for
longer than that. A write that fails or times out closes the connection
and fires its `onClose` callback, so the app's cleanup runs. The `app.ws()` callback itself also runs on its own
virtual thread rather than Undertow's shared I/O pool, so a connect
handler that blocks can't stall other connections' handshakes.

`app.set( "wsIdleTimeoutMs", ms )` (default `0`, off) drops a connection
that has sent nothing — no message, no ping, no pong — for that long, and
fires its `onClose`. The server pings every third of that period, and a
healthy client (every browser, automatically) answers, so a quiet but
alive connection is never dropped; one whose peer vanished, or that
ignores pings, is. It's opt-in because the right value depends on your
clients; `60000` is a reasonable start.

Incoming messages are capped at 1 MB by default — a larger one is
refused and the connection closed with a `1009` (message too big) — so an
unauthenticated client can't make the server buffer an unbounded message.
Change it with `app.set( "wsMaxMessageSize", bytes )` before `listen()`.
Response header values (`res.set()`, `res.cookie()`) containing CR/LF,
other control characters, or characters above Latin-1 are refused with a
500 rather than sent, since Undertow would silently narrow those to
8 bits.

**Origin check.** A browser sends cookies with the WebSocket handshake
whichever site the page is on, so without a check any website could open
an authenticated socket as its visitor (cross-site WebSocket hijacking).
`app.ws()` routes accept only same-origin pages by default — the page's
`Origin` must match the `Host` it connected to (or `X-Forwarded-Host` from
a trusted proxy); others get a `403` before the upgrade. Clients that send
no `Origin` (servers, CLI tools, the cluster relay) aren't browsers and are
let through. Change it per route or for all routes:

```js
app.ws( "/chat", handler, { origins: [ "https://app.example.com" ] } )
app.set( "wsOrigins", "*" )   // the old allow-everything behavior
```

Path matching is exact only for now — no `:params`, no mounting under a
`Router`. An upgrade request to a path with no registered `app.ws()`
route falls through to the normal HTTP dispatch chain untouched, so it
gets whatever that path would otherwise return (a `404` if nothing
matches there either) rather than being silently accepted as a
WebSocket connection. An app that registers no `app.ws()` routes at all
pays nothing for this — the plain HTTP dispatch chain is wired directly
into Undertow with no extra indirection in front of it.

Built on one small piece of compiled Java
(`java-src/boxexpress/ws/`, vendored as `libs/boxexpress-ws-shim-*.jar`) —
the only compiled Java in this project. See
[docs/ARCHITECTURE.md](ARCHITECTURE.md#the-one-piece-of-compiled-java-java-srcboxexpressws)
for why.
