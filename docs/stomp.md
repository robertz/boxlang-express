# STOMP (`boxExpressStomp()` / `Stomp`)

A [STOMP](https://stomp.github.io/) 1.0/1.1/1.2 pub/sub broker built entirely on
top of `app.ws()` — destination-based messaging (`SUBSCRIBE`/`SEND`) over
a WebSocket connection, the shape most chat/notification/live-update use
cases actually need:

```js
stomp = boxExpressStomp()
app.ws( "/stomp", stomp.handler() )

app.post( "/orders", ( req, res ) => {
	// ... create the order ...
	stomp.send( "/topic/orders", { orderId: newOrder.id } )
	res.json( { created: true } )
} )
```

Or via the underlying class directly:

```js
stomp = new bxModules.boxexpress.models.middleware.Stomp( {
	authenticate: ( login, passcode, host, connection, connectionMetadata ) => {
		connectionMetadata.role = login == "admin" ? "admin" : "guest"   // read back in authorize() below
		return login == "demo" && passcode == "demo"
	},
	authorize: ( login, destination, access, connection, connectionMetadata ) => {
		return destination != "/topic/admin-only" || connectionMetadata.role == "admin"
	},
	heartbeatMs: 10000
} )
app.ws( "/stomp", stomp.handler() )
```

`authenticate(login, passcode, host, connection, connectionMetadata)`
gates `CONNECT` — return `false` and the client gets an `ERROR` frame and
the connection closes. `authorize(login, destination, access,
connection, connectionMetadata)` gates each `SUBSCRIBE`/`SEND` (`access`
is `"subscribe"` or `"publish"`) — return `false` and that one frame gets
an `ERROR` instead of taking effect; the connection stays open. Both are
optional — omit either to allow everything. `connection.cookies`/
`connection.headers` (from the WebSocket handshake, before any STOMP
frame arrives) are available on the `connection` passed to both,
alongside STOMP's own `login`/`passcode` frame headers — read a session
cookie here for cookie-based auth instead of requiring clients to send
credentials in the `CONNECT` frame.

`connectionMetadata` is a plain struct, empty until `authenticate()`
populates it — mutations persist for the life of the connection (it's
the same struct instance every later `authorize()` call for that
connection receives) and are echoed back to the client as extra
`CONNECTED` headers. Attach whatever per-connection state your app needs
once at connect time (a user id, a tenant, roles) instead of
re-deriving it on every frame.

`stomp.send(destination, data, headers)` is the server-side publish path
for app code outside any connection's own message handling — a route
handler pushing an update, the same broadcast pattern
`WebSocketConnection`/`SseEmitter` already support. `data` is
JSON-serialized unless it's already a simple value.

**Receipts.** Any client frame carrying a `receipt` header gets a
`RECEIPT` frame back once it's been processed — not just `DISCONNECT`.
If that frame's processing itself failed, the `ERROR` frame carries the
matching `receipt-id` instead.

**Acknowledgment (`ACK`/`NACK`).** `SUBSCRIBE`'s `ack` header —
`"auto"` (default), `"client"`, or `"client-individual"` — controls
whether the client must explicitly acknowledge each `MESSAGE`. Under
`"client"`/`"client-individual"`, every `MESSAGE` carries an `ack`
header; the client references it in a later `ACK`/`NACK` frame's `id`
header. `"client"` mode is cumulative (acking one message also acks
every earlier unacked message on that subscription, per spec); an
unknown/already-acked `id` gets an `ERROR`. `NACK` is bookkeeping-
identical to `ACK` here: this broker delivers straight to open
connections with no message queue to redeliver into, so the positive/
negative distinction real brokers use to drive redelivery has nothing to
act on.

**Transactions.** `BEGIN transaction:tx-1` / `COMMIT` / `ABORT` scope a
set of `SEND`/`ACK`/`NACK` frames (via their own `transaction` header) so
they take effect together on `COMMIT` or are discarded on `ABORT` —
scoped to one connection, per spec. A `RECEIPT` for a buffered frame is
sent when the frame is accepted into the transaction, not deferred to
`COMMIT` (STOMP's receipt semantics are "frame accepted," not
"transaction contents individually confirmed").

**Heartbeats**, negotiated per spec section 6: send a `heart-beat:cx,cy`
header on `CONNECT` (`cx` = fastest interval you can send at, `0` if you
can't; `cy` = interval you want to receive at, `0` if you don't want
any) and the server computes both directions independently —
`heartbeatMs` (if configured) is this server's own capability in both
directions, `max()`'d against what the client asked for. A client that
sends no `heart-beat` header defaults to `"0,0"` (no heartbeats either
way) even if the server is configured for them. When an incoming
interval is negotiated, the server also monitors it: if nothing arrives
(a real frame or a bare heartbeat) within roughly 2x that interval, it
sends an `ERROR` and closes the connection.

**Message headers.** Application headers on a client `SEND` (`reply-to`,
`correlation-id`, anything custom) are forwarded on the `MESSAGE` frame;
the headers the protocol or broker owns (`destination`, `transaction`,
`receipt`, `content-length`, `message-id`, `subscription`, `ack`) are not
copied, so a client can't forge them. Frames may use `\n` or `\r\n` line
endings, and `content-length` is an octet count, so multi-byte bodies
round-trip.

**Limits.** Each bounds what clients can make the broker hold or wait on; pass `0`
to disable one. The first seven are per connection, the last two are
per node. Exceeding a cap gets an `ERROR` and leaves the connection
open, except where noted:

| Option | Default | Effect |
|---|---|---|
| `connectTimeoutMs` | `10000` | a socket that hasn't completed `CONNECT` in time is closed |
| `maxFramesPerSecond` | `0` (off) | more inbound frames per second closes the connection — the right value is app-specific, so it's opt-in |
| `maxSubscriptions` | `100` | subscriptions per connection |
| `maxTransactions` | `16` | open transactions per connection |
| `maxTransactionFrames` | `1000` | frames buffered per transaction |
| `maxPendingAcks` | `1000` | unacknowledged messages per connection — past it the connection is closed, since a client that never acks would otherwise grow this forever |
| `maxDestinationLength` | `255` | characters in a `SUBSCRIBE`/`SEND` destination |
| `maxConnections` | `10000` | connections per node that have completed `CONNECT`; further `CONNECT`s get an `ERROR` and are closed, before `authenticate()` runs. Not atomic, so a burst of simultaneous `CONNECT`s can overshoot slightly |
| `maxSubscribersPerDestination` | `10000` | subscriptions on one destination (per node); the `SUBSCRIBE` gets an `ERROR` and the connection stays open |

A subscription `id` already in use on the same connection is rejected
with an `ERROR` (ids must be unique per connection, per spec). An
`authenticate`, `authorize` or `onSend` callback that throws no longer
leaves the client with no reply: the exception is logged server-side, and
the client gets a generic `ERROR` (authenticate and authorize fail
closed). A destination's registry entry is dropped once its last
subscriber leaves.

**Protocol versions.** `CONNECT`'s `accept-version` is negotiated and the
connection is served in the highest version both sides offer (no header
means 1.0). Per version: 1.2 escapes `\\`, newline, CR and `:` in
header values, 1.1 the same minus CR, 1.0 not at all (and `CONNECT`/
`CONNECTED` frames are never escaped); 1.0/1.1 clients acknowledge by
`message-id` instead of 1.2's separate `ack` header; 1.0 clients may
`SUBSCRIBE` without an `id` and `UNSUBSCRIBE` by `destination`; and
heart-beating only exists from 1.1. Only `MESSAGE` frames follow the
connection's version for escaping; `ERROR`/`RECEIPT` use 1.2's. An
unsupported version gets an `ERROR` listing `1.0,1.1,1.2`. A second
`CONNECT` on an already-connected socket is an `ERROR`.

**Lifecycle hooks.** All optional observers — a hook that throws is
logged and ignored, and can't affect the connection:

```js
stomp = boxExpressStomp( {
	onConnect:     ( login, connection, connectionMetadata, connectionId ) => { ... },
	onDisconnect:  ( login, connectionMetadata, connectionId ) => { ... },
	onSubscribe:   ( login, destination, subscriptionId, connection, connectionMetadata ) => { ... },
	onUnsubscribe: ( login, destination, subscriptionId, connectionMetadata ) => { ... }
} )
```

`onDisconnect` fires for every connection that completed `CONNECT`,
however it ended (client close, dropped socket, server close, a failed
write); just before it, `onUnsubscribe` fires for each subscription the
connection still held. `stomp.disconnect(connectionId, message)` closes a
connection from the server side — with an `ERROR` frame carrying `message`
first, if given — and returns `false` if that connection isn't on this
node.

**Targeted delivery.** `stomp.sendToUser(login, destination, data,
headers)` publishes to `destination` (through the exchanges, like any
publish) but only to subscriptions whose connection authenticated with
that `login` — every connection the user has open, no one else's.
`stomp.sendToConnection(connectionId, destination, data, headers)` does
the same for one connection (a key of `getConnections()`). Both return
how many subscriptions on this node received it, don't invoke
server-side `listeners`, and, with `options.cluster`, are relayed so the
recipient is reached on whichever node holds their connection.

**Presence.** `stomp.getClusterPresence()` returns `{ logins, byNode }` —
the distinct logins connected across the cluster and each node's own
list. Nodes publish their list on the cluster heartbeat, so a peer's
entry can lag by up to one heartbeat interval: fine for "who's online,"
not for anything that must be exact. Without clustering it's just this
node. `getConnections()` remains local to the node.

**Delivery.** A publish writes to its subscribers concurrently, so
unresponsive subscribers cost one slow write in total rather than one
each in sequence; a subscriber whose connection turns out to be closed is
dropped from the registry immediately. Heartbeats run on virtual threads,
not one platform thread per connection.

`Sec-WebSocket-Protocol` negotiation (`v12.stomp`/`v11.stomp`/
`v10.stomp`) is handled automatically by `app.ws()`'s handshake, for
client libraries (stomp.js and others) that send and expect it echoed —
harmless to every other `app.ws()` route, since a client that never
sends that header skips negotiation entirely.

**Exchanges & bindings** — AMQP-style destination routing, not part of
core STOMP itself but a proven pattern real STOMP brokers (including
Ortus's own [SocketBox](https://github.com/coldbox-modules/SocketBox),
whose exchange design this mirrors) layer on top. A destination prefixed
`"exchangeName/rest"` routes through the exchange registered under that
name; anything else (no `"/"`, or a prefix that isn't a configured
exchange name) routes through the always-present `direct` exchange with
the whole destination string as the routing key — so every destination
that never uses the `"exchangeName/..."` convention behaves exactly as
it did before exchanges existed:

```js
stomp = new bxModules.boxexpress.models.middleware.Stomp( {
	exchanges: {
		direct: { bindings: { "orders.created": "notifications" } },
		topic: { bindings: { "chat.room.*": "chatFanout", "sys.##": "sysAll" } },
		fanout: { bindings: { "myFanout": [ "dest1", "dest2" ] } },
		distribution: { type: "roundrobin", bindings: { "myDist": [ "destA", "destB" ] } }
	}
} )
```

- **`direct`** (always configured even if omitted) — routes a destination
  through `bindings` if one matches, or passes it through unchanged
  otherwise.
- **`topic`** — wildcard matching over dot-separated segments: `*`
  matches exactly one segment, `#` matches zero or more remaining
  segments (the standard AMQP convention — note `#` needs to be written
  as `##` inside a BoxLang string literal, since a bare `#` starts
  string interpolation; `"sys.##"` is the one-character pattern `sys.#`,
  confirmed directly). Every binding whose pattern matches gets routed
  to, not just the first — a topic message can reach multiple
  destinations.
- **`fanout`** — one binding name routes to every destination in its
  array.
- **`distribution`** — the opposite of fanout: one binding name routes to
  exactly one destination from its array, chosen by `"random"` (default)
  or `"roundrobin"`.
- **A custom exchange** — any name other than the four built-ins, with a
  `class` pointing to a dotted class path implementing `route(destination,
  body, headers)` (returning an array of physical destinations to
  deliver to) — duck-typed, no formal interface, matching how this
  codebase treats every other callback-shaped middleware option.

`getExchanges()` returns the configured exchange instances (for
debugging — see the introspection getters below).

**A live connection registry.** `stomp.getConnections()` returns every
currently-`CONNECT`ed client, keyed by an internal connection id, each
entry `{ connection, login, connectionMetadata }` — automatically
populated on `CONNECT` and cleared on disconnect. `stomp.
getConnectionDetails(connectionId)` reads one entry. `stomp.
getSubscriptions()` and `stomp.getConfig()` round out the introspection
surface (mirroring SocketBox's own `getSTOMPConnections()`/
`getConnectionDetails()`/`getSubscriptions()`/`getConfig()`) — these
return live internal structs, meant for debugging/ops, not as a stable
data API to build app logic against.

**Server-side listeners** — react to messages routed to a destination
from plain server code, without opening a WebSocket connection:

```js
stomp = new bxModules.boxexpress.models.middleware.Stomp( {
	listeners: {
		"audit-log": ( message ) => {
			logMessage( message.getBody() )   // auto-JSON-deserialized if content-type is application/json
			// message.getConnection() is the originating WebSocketConnection,
			// or null if this arrived via stomp.send() from server code
		}
	}
} )
```

A listener's own exception is caught and logged, not allowed to break
delivery to real subscribers or other listeners on the same destination.
See [StompMessage.bx](../models/StompMessage.bx) for the message object's
full API (`getCommand()`/`getBody()`/`getBodyRaw()`/`getHeaders()`/
`getHeader()`/`getConnection()`).

This is a real, if intentionally smaller, first version — not the whole
STOMP ecosystem some brokers support. Deliberately not built: **binary
bodies** — inbound frames may arrive as binary WebSocket messages
(decoded as UTF-8), but outbound frames are always text, and
`content-length` is honored on read/write so a body containing an
embedded NUL byte round-trips correctly while genuinely non-UTF-8 octets
can't. Also not built: message **redelivery** (`NACK` and unacknowledged
messages just clear bookkeeping — there is no queue to redeliver into).
Destination matching (both the subscriber registry and
exchange bindings) is exact-string only — `/topic/a` and `/topic/a/`
are different destinations.

To relay a publish to every other process in a cluster instead of just
this one's local subscribers, see `options.cluster` under [Cluster
support](cluster.md).

Header values are escaped per the STOMP spec (backslash, newline, colon),
not just stripped — a destination or login value built from
request-derived input can't break a frame's structure, the actual
protocol-correct version of the same fix `SseEmitter.bx` needed. See
[StompFrameCodec.bx](../models/StompFrameCodec.bx) and
[Stomp.bx](../models/middleware/Stomp.bx).
