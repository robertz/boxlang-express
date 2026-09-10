# WebSocket + STOMP support — design

**Status: built and shipped.** Both layers described below exist: the raw
`app.ws()` WebSocket addon (`models/BoxExpress.bx`'s `ws()`,
`models/adapters/WsUpgradeRouter.bx`, `WsConnectionCallback.bx`,
`WsMessageHandlerAdapter.bx`, `models/WebSocketConnection.bx`, and the one
real-Java shim, `java-src/boxexpress/ws/BoxWebSocketListener.java` — see its
own docblock for why a compiled class was unavoidable) and the STOMP broker
on top of it (`models/middleware/Stomp.bx`, exercised by
`tests/specs/StompSpec.bx`/`StompParitySpec.bx`). Kept as a historical design
record below rather than rewritten into after-the-fact documentation — most
of the reasoning held up; the "Implementation notes" callouts mark the
places the real code ended up differently, and "Explicit non-goals" has been
corrected where a "cut" feature actually shipped.

Everything below was written before any of it existed — read the file-list
above as ground truth over specific class/file names mentioned further down.

## Why two layers, not one

[SocketBox](https://github.com/coldbox-modules/SocketBox) — Ortus's own
WebSocket library — has exactly this shape: a `WebSocketCore` class (raw
connect/message/close) and a `WebSocketSTOMP` class that extends it with
routing, subscriptions, auth. Worth copying that layering, not just the
STOMP feature list: raw WebSocket support is useful on its own (a simple
broadcast, a live cursor-position feed, anything that doesn't need topics
or routing), and STOMP is a specific, opinionated protocol for the cases
that do. Bolting STOMP semantics directly onto the transport would force
every WebSocket user through STOMP's frame format even when they don't
want it.

SocketBox itself can't be reused here — confirmed directly: it plugs into
a WebSocket upgrade listener baked into CommandBox 6.1+/BoxLang
MiniServer's own binaries, dispatching each message as a synthetic
internal HTTP request to a `/WebSocket.cfc?method=onProcess` convention
path. That's the `boxlang-web-support` runtime layer this project
deliberately doesn't load (same reason BoxLang's own `SSE()` BIF isn't
reachable from here either). Its *design* is the reusable part, not its
code.

## Layer 1 recap: the raw WebSocket addon

(Previously scoped in more detail; recapped here since that plan file is
gone. Re-verify the dependency claims below before starting — they were
checked once, not continuously.)

- **No new dependency.** `libs/undertow-core-2.4.2.Final.jar` already
  contains the low-level WebSocket API
  (`io.undertow.websockets.core.WebSocketChannel`,
  `io.undertow.websockets.WebSocketProtocolHandshakeHandler`,
  `WebSocketConnectionCallback`) — confirmed via the jar's own class
  listing. `undertow-websockets-jsr` (the heavier `javax.websocket`
  annotation API) was deliberately excluded and stays excluded.
- **Ships as a separate addon module**, not baked into core
  `BoxExpress.bx` — most apps don't need WebSockets, and core should stay
  unaware of them entirely when the addon isn't installed. The hook: a
  custom BoxLang interception point (`customInterceptionPoints` in
  `ModuleConfig.bx`'s `configure()`, confirmed as a real, documented
  mechanism — not a maybe) that core announces once per request, right
  before the normal HTTP dispatch:

  ```js
  var event = { exchangeAdapter: adapter, handled: false };
  boxAnnounce( "beforeDispatch", event );
  if ( !event.handled ) {
      bridge.handle( adapter );   // existing HTTP path, unchanged
  }
  ```

  The WebSocket addon's own interceptor listens for `beforeDispatch`,
  checks for an `Upgrade: websocket` header, and if it matches, takes over
  the raw exchange itself and sets `event.handled = true`.
- **New API:** `app.ws(path, connectionHandler)`, registered separately
  from `app.get/post/...` since there's no `req`/`res`/`next()` chain for
  a persistent connection. `connectionHandler` receives a `WebSocketConnection`
  — same adapter pattern as `Request`/`Response`, mirroring `SseEmitter`'s
  shape where it makes sense: `connection.onMessage(callback)`,
  `connection.send(data)`, `connection.onClose(callback)`,
  `connection.close()`.
- **Threading:** every connection gets its own virtual thread for its
  receive loop, same model as every HTTP request already gets — no
  bounded-pool concerns, matching the reasoning that already worked out for
  `res.sse()`.

## Layer 2: STOMP, built on `app.ws()`

STOMP (Simple Text Oriented Messaging Protocol) is a small, text-based
framing protocol on top of WebSockets — `COMMAND\nheader:value\n\n<body>\0`.
Client frames: `CONNECT`/`STOMP`, `SEND`, `SUBSCRIBE`, `UNSUBSCRIBE`,
`ACK`/`NACK`, `BEGIN`/`COMMIT`/`ABORT`, `DISCONNECT`. Server frames:
`CONNECTED`, `MESSAGE`, `RECEIPT`, `ERROR`.

### Adapting SocketBox's design to this project's conventions

SocketBox's STOMP layer is a `WebSocketSTOMP` class an app **extends**,
overriding methods like `authenticate()`/`authorize()`/`configure()`.
That's the CFML-component-inheritance idiom. It doesn't fit this
project's own established pattern — every other piece of BoxExpress
(Helmet, CORS, RateLimit, Session, Csrf) is a factory function taking an
**options struct**, including callback-shaped options
(`RateLimit`'s `keyGenerator`, `Session`'s `store` object). STOMP support
should follow that shape instead:

```js
stomp = new middleware.Stomp( {
    heartbeatMs: 10000,

    authenticate: ( login, passcode, host, connection ) => {
        return login == "demo" && passcode == "demo"
    },

    authorize: ( login, destination, access, connection ) => {
        // access is "subscribe" or "publish"
        return true
    },

    // Optional. Runs on a client SEND, after authorize() passes, before
    // the frame is relayed to subscribers. Return false/null to absorb
    // the frame (nothing delivered — e.g. a presence-heartbeat SEND that
    // should update server-side bookkeeping and broadcast a computed
    // value instead of the raw ping), {body:, headers:} to transform what
    // gets delivered, or true/omit a return to pass the frame through
    // unchanged (the default when onSend isn't provided at all).
    onSend: ( destination, body, headers, connection, connectionMetadata ) => {
        return true
    },

    exchanges: {
        "direct": {
            bindings: { "orders.created": "notifications" }
        },
        "topic": {
            bindings: { "chat.room.*": "chatFanout" }
        }
    }
} )

app.ws( "/stomp", stomp.handler() )
```

`stomp.handler()` returns exactly the `(connection) => {...}` shape
`app.ws()` already expects — STOMP is implemented entirely as a consumer
of the Layer 1 API, not a special case inside it.

### Frame parsing

> **Implementation note:** shipped as `models/StompFrame.bx` (the parsed
> frame struct) plus `models/StompFrameCodec.bx` (`parse()`/`serialize()`),
> and `models/StompMessage.bx` (the listener-facing wrapper `options.listeners`
> callbacks actually receive — see its own docblock for why that's a separate
> shape from `StompFrame`).

A dedicated `StompFrame.bx`: `parse(rawText)` → `{ command, headers,
body }`, and `toFrame()`/`serialize()` the other direction. Text-only to
start (STOMP allows binary bodies via `content-length`; deferred — see
scope below). Verify against the STOMP 1.2 spec's actual grammar with a
handful of real captured frames before trusting hand-written parsing,
same "verify before assert" standard as everything else in this project —
frame parsing is exactly the kind of thing that looks simple and has
edge cases (escaped colons/newlines in header values, an absent
`content-length`, trailing NUL handling).

### Exchange/binding routing

> **Implementation note:** the four exchange types shipped exactly as
> proposed, under `models/middleware/stomp/exchanges/` (`DirectExchange.bx`,
> `TopicExchange.bx`, `FanoutExchange.bx`, `DistributionExchange.bx`), plus
> support for a fully custom exchange via `config.class`. `StompRouter.bx`
> didn't end up as its own file, though — routing, the subscription
> registry, connection registry, transactions, and ack/nack bookkeeping all
> live directly in `models/middleware/Stomp.bx` itself instead of being
> split across dedicated `StompRouter.bx`/`StompSubscription.bx`/
> `StompSubscriptionRegistry.bx` classes as sketched below. One file turned
> out simpler than the proposed split once the locking (see the sharded
> per-destination/per-connection locks noted in that file) had to reason
> about all of it together.

SocketBox's model — `direct`, `topic`, `fanout`, `distribution` exchanges,
each with a `bindings` map — is worth copying close to verbatim; it's a
proven design (itself AMQP-shaped, not invented by SocketBox) with real
production usage behind it, not something worth redesigning from scratch:

- **direct**: exact destination-name match, one binding → one target
- **topic**: wildcard patterns (`chat.room.*`, `##` for multi-segment)
  matched against a destination, routed to a target
- **fanout**: one binding name → an array of targets, all get the message
- **distribution**: fanout's opposite — one binding → an array of
  targets, message goes to exactly one (round-robin or random)

A `StompExchange.bx` per type, a `StompRouter.bx` that owns the configured
exchanges and does `route(destination) → [subscriptions]`.

### Subscriptions

`StompSubscription.bx`: `{ id, connection, destination }`. A
`StompSubscriptionRegistry.bx` — `subscribe(connection, destination, id)`,
`unsubscribe(connection, id)`, `getSubscriptions(destination)`,
`removeAllFor(connection)` (called from the connection's `onClose`, so a
disconnected client's subscriptions don't linger). Needs the same
per-connection write lock `SseEmitter` already uses and already proved
under real concurrent load — multiple STOMP `SEND`s routing to the same
subscriber's connection concurrently is the exact same shared-`OutputStream`
hazard SSE's broadcast pattern had, and the fix is the same shape.

### Server-side send

Mirrors `SseEmitter`'s emitter-stashed-and-called-from-elsewhere pattern,
not SocketBox's `send()`-on-an-instance-you-extended:

```js
app.post( "/orders", ( req, res ) => {
    stomp.send( "orders.created", { orderId: newOrder.id } )
    res.json( { created: true } )
} )
```

`stomp.send(destination, data, headers)` looks up the destination's
subscribers via the router, serializes `data` (JSON unless already a
simple value, matching `SseEmitter.send()`'s existing convention) into a
`MESSAGE` frame per recipient, writes each through that connection's own
lock.

### Heartbeats

> **Implementation note:** shipped per-connection (not one background
> thread for the whole broker) — each connection negotiates its own
> incoming/outgoing intervals off the `CONNECT` frame's `heart-beat` header,
> per the STOMP spec's actual negotiation semantics described below, rather
> than a single fixed `heartbeatMs` applied uniformly. A connection whose
> promised heartbeat goes quiet is closed with an `ERROR`, matching the
> "treated as dead and cleaned up" behavior sketched here.

A background thread (or a `sleep()` loop on its own virtual thread,
started once when the STOMP broker is created, not per-connection) sends
an empty-body newline frame to every open connection every
`heartbeatMs`, and expects one back within some multiple of that interval
— a connection that goes quiet gets treated as dead and cleaned up
(subscriptions removed, socket closed). SocketBox does this per the STOMP
spec's own heartbeat negotiation (`heart-beat` header in `CONNECT`); worth
matching that negotiation rather than a fixed interval, so clients that
ask for a different cadence get it.

## Non-goals — original list, corrected against what actually shipped

- **Binary STOMP bodies.** Still not built — text/JSON only, matching this
  project's `SseEmitter.send()` convention.
- ~~**Transactions** (`BEGIN`/`COMMIT`/`ABORT`).~~ **Shipped.** Full
  transaction buffering — a `SEND`/`ACK`/`NACK` inside a transaction is
  deferred until `COMMIT`, discarded on `ABORT` — see `Stomp.bx`'s
  `_handleSend()` docblock for exactly when authorization/`onSend` are
  (re-)evaluated for a deferred, transactional `SEND`.
- ~~**`ACK`/`NACK`** (client acknowledgment of message receipt).~~
  **Shipped.** `auto`/`client`/`client-individual` ack modes, cumulative
  ack semantics for `client` mode. NACK is bookkeeping-identical to ACK —
  see `_handleAcknowledge()`'s own docblock for why the positive/negative
  distinction real brokers use to drive redelivery has nothing to act on
  here (no message queue to redeliver into).
- **Auto-reconnect.** Still correctly out of scope — client-side behavior
  (the JS client library's own `reconnectDelay`, not anything the server
  implements). Still not built and still not needed here.
- **Not originally listed, cut anyway:** multi-node clustering (SocketBox's
  `ClusterManager`/`ClusterPeer`) — see `Stomp.bx`'s own docblock. This
  project has no cache-abstraction or multi-node deployment story to build
  it against yet; the whole subscriber/connection registry is explicitly
  single-process state as a result.

## Suggested phasing

All phases below shipped. Left as a record of the order this was actually
built and verified in — same discipline as every other multi-step feature
in this project, verify each phase live before starting the next:

1. **Spike Layer 1 alone**, standalone from BoxExpress's architecture:
   prove `WebSocketProtocolHandshakeHandler` + a raw
   `WebSocketConnectionCallback` actually works against Undertow directly,
   the same shape as the original Phase 1 Undertow classloading spike.
2. **Wire the `beforeDispatch` hook into core** (a trivial listener module
   that just proves the hook fires and can claim a request) before any
   real WebSocket code exists.
3. **Build `app.ws()` for real** — connection wrapper, `onMessage`/`send`/
   `onClose`/`close()`, a basic echo-server test.
4. **`StompFrame.bx` parse/serialize**, tested against real captured STOMP
   frames, independent of any connection handling.
5. **`StompRouter.bx` + exchanges/bindings**, unit-tested with fake
   subscriptions — no real WebSocket connection needed yet.
6. **Wire STOMP onto `app.ws()`** — `CONNECT`/`SUBSCRIBE`/`SEND`/
   `DISCONNECT` end to end against a real connection.
7. **Heartbeats**, **auth hooks**, **`stomp.send()` from arbitrary app
   code** (the broadcast-from-elsewhere pattern — needs the same
   concurrency stress test `SseEmitter` got: multiple threads sending to
   overlapping subscriber sets, checked for corrupted frames, not just
   trusted).
8. **Security pass** before calling it done — the same review that caught
   `SseEmitter`'s newline-injection bug should run again here.
   STOMP headers are exactly the same shape of risk: a `destination` or
   `login` value reflected into a frame without checking for embedded
   `\n`/`\0` is the same class of bug, just in a different protocol.
