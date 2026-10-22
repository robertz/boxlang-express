# Changelog

**0.2.22**
- **`reloadOnChange` runs a supervisor** (`models/BoxExpress.bx`): the
  process you start now stays alive and runs your app as a child process,
  restarting the child on a `.bx`/`.bxs` change. Before, a restart spawned
  a replacement and exited, so the process a shell, IDE or container
  started was gone after the first save: Ctrl+C no longer reached the
  server, a supervisor saw a clean exit (a container, where it was PID 1,
  stopped outright), and a startup error in the saved code left
  nothing watching for the fix. Now:
  - the old child has exited before the new one starts, so the port
    handoff no longer depends on BoxLang booting slower than `close()`;
  - a child that crashes is reported (`app exited with code N`) and
    started again on the next save;
  - `SIGTERM` or Ctrl+C to the supervisor stops the child, and a child
    whose supervisor is killed outright exits within a second;
  - `app.schedule()` jobs and the cluster manager started before
    `listen()` are shut down in the supervisor, so they run only in the
    child;
  - a reload child stops immediately on restart by default
    (`shutdownTimeoutMs` still overrides it), so an open SSE stream
    doesn't hold up every save.
- **Behavior changes:** dev mode now runs two JVMs; code before
  `app.listen()` runs in both (code after it, and the `listen()`
  callback, only in the child). The docs also correct the watch root:
  it is the entry script's directory, not the working directory.
- **Docs** (`docs/auto-restart.md`): how the supervisor behaves, and
  using `watchexec`, `nodemon` or `entr` instead of `reloadOnChange`.
- `ProcessLifecycleSpec`: the reload test now checks the supervisor
  survives the restart, plus new cases for crash-then-fix recovery and
  `SIGTERM` to the supervisor.

**0.2.21**
- **Sessions** (`models/middleware/Session.bx`): a changed session is
  written to the store in `res.onBeforeSend()`, before the response goes
  out, not only after `next()` returns. With an out-of-process store the
  old order raced the client's next request: fetch a CSRF token, then POST
  the login form, and the POST could load the session before the token
  was written. Feedhouse carried a workaround middleware for this. The
  after-`next()` save stays for a chain that sent nothing, a store that
  failed before send (logged, not thrown, since the response is already
  marked sent), and a change made after sending, which is now compared
  case-sensitively. Still one write per request otherwise.
- **JSON bodies** (`models/middleware/BodyParsers.bx`): a body that
  doesn't parse gets a `400` (`{ error: true, message: "Malformed JSON
  body" }`) directly, like the `413` beside it, instead of passing the
  parse exception to `next()`, where it became a `500`.
- **Behavior change:** error-handling middleware no longer sees malformed
  JSON; an app that turned it into its own `400` can drop that.
- New `SessionPersistSpec`, and a malformed-body case in `InjectSpec`.

**0.2.20** — security and performance pass. Several defaults changed; see
**Behavior changes** below before upgrading.
- **Routing:** skipping routes no longer recurses once per skipped layer.
  An app with ~550 routes and middleware overflowed the stack on any 404
  (500 error); 3,000+ now work, and a 404 past 500 routes went from 258 ms
  to about 22 ms.
- **Static files:** dotfiles are ignored by default (`/.env` was served);
  `dotfiles: "deny" | "allow"`; `/.well-known/` always served. Added
  `.mjs`, `.webp`, `.wasm`, `.map` and other common types (ES modules were
  served as `application/octet-stream`).
- **Streamed file responses:** `res.sendFile()`, `res.download()` and
  static files stream from disk instead of reading the whole file into
  memory per request (`HEAD` included). Range offsets past 2 GB were
  clamped to the same wrong position; fixed.
- **`trust proxy`** takes a hop count or a list of proxy addresses/CIDRs,
  as in Express, so `req.ip` (and the rate-limit key) can't be picked by a
  client behind a proxy that appends to `X-Forwarded-For`.
  `X-Forwarded-Proto`/`-Host` are believed only from a trusted peer.
- **Cluster relay:** `secretKey` (16+ characters) is now required to relay
  STOMP messages — an unset one used to leave `/__cluster` open to anyone
  who could reach the port. Compared in constant time, case-sensitively.
- **CSRF:** the token is compared in constant time and case-sensitively
  (BoxLang's `!=` is neither); a JSON array body no longer errors.
- **WebSockets:** same-origin check on `app.ws()` handshakes, with
  `{ origins }` per route and `app.set("wsOrigins")`.
- **CORS:** `credentials: true` with `origin: true` or `"*"` now throws;
  `Vary: Origin` is sent whenever the response depends on `Origin`.
- **Sessions:** `req.regenerateSession()` for login; the in-memory store
  is capped by `maxSessions` (default 100,000) and sweeps expired entries
  at most every 30 seconds instead of scanning on every 100th write.
- **Uploads:** the stored extension is kept only if alphanumeric and
  ≤10 characters, with a containment check on the written path. The
  parser copies part contents straight from the request bytes instead of
  making several whole-body copies.
- **Conditional GET:** `If-None-Match` handles lists, `*`, and weak tags,
  and compares case-sensitively.
- **Removed Handlebars views** (`.hbs`) and the vendored
  `handlebars-4.3.1.jar`, which carried an unrelated advisory.
- `Response` builds its status and MIME tables once per class instead of
  per request.
- **Behavior changes:**
  - `.hbs` views throw `BoxExpress.UnsupportedViewEngine`; convert them to
    `.bxm`.
  - Dotfiles under `boxExpressStatic()` are no longer served (use
    `dotfiles: "allow"` if you relied on it).
  - `app.ws()` refuses cross-site browser origins (use `origins` or
    `app.set("wsOrigins", "*")`).
  - A clustered STOMP broker won't start without a 16+ character
    `secretKey`.
  - `boxExpressCors({ credentials: true })` without an explicit origin
    throws.
  - `trust proxy: 1` (or any number) now means one hop, as in Express, not
    "trust everything".
- Full suite: 403/403.

**0.2.19**
- **Request IDs:** `app.set( "requestId", true )` — `req.id`, an
  `X-Request-Id` response header (a well-formed one from the client is
  reused, anything else replaced), and the id in the request and
  unhandled-error log lines. Off by default.
- **`app.metrics()`:** a Prometheus-format `/metrics` endpoint — requests
  by status class and duration, Undertow connections and bytes, open
  WebSockets, draining state, per-job scheduler runs/failures/skips/last
  success, cluster peers and manager status, and any `sources` (a STOMP
  broker's connections, subscriptions and message counts). Optional bearer
  `token`. A scheduled job that has quietly been failing now shows up as a
  rising failure counter and a stale last-success time. Full suite:
  380/380, also verified in the `cli-1.16.0`, `cli-1.17.0` and `cli`
  container images CI uses.

**0.2.18**
- **`app.inject()`:** run a request through an app in-process, with no
  port, and get back `statusCode`, `body`, `headers`, `json()`, `header()`
  and the cookies the response set (`cookies`, ready to pass into the next
  call to carry a session). Backed by a new in-memory exchange adapter
  behind the same `Request`/`Response`/`HttpBridge` a real request uses.
  The response-header value check moved into a shared `HeaderGuard` so the
  real server and injected requests reject the same values. Full suite:
  361/361.

**0.2.17**
- **`app.health()` and `app.shutdown()`:** liveness/readiness endpoints for
  load balancers and Kubernetes, and a graceful stop that flips readiness
  to draining, refuses new requests, closes open WebSockets and waits up
  to `timeoutMs` for in-flight requests. `SIGTERM`/Ctrl-C now run it
  (`shutdownTimeoutMs`, default 5s; `0` restores the old immediate stop).
- **`wsIdleTimeoutMs`:** opt-in drop of WebSocket connections that go
  silent, with server pings so healthy quiet connections survive. The
  other half of CVE-2026-81624 (unbounded connection lifetime) alongside
  0.2.13's message-size cap.
- **Shared rate limiting:** `boxExpressRateLimit( { cache: "name" } )`
  (or `store:`) counts in a BoxLang cache so the limit holds across
  instances. `boxExpressSession( { cache: "name" } )` is shorthand for the
  cache-backed session store.
- **Graceful fallback:** the cache-backed stores now degrade to
  per-process memory instead of failing requests when the named cache is
  missing or a durable store fails at runtime (logged, and recovering on
  its own). **Behavior change:** `boxExpressCacheStore( "missing" )` used
  to throw on first use; it now falls back with a warning — pass
  `{ fallback: false }` for the old behavior.
- The server now tracks open WebSocket connections so shutdown can close
  them. Full suite: 340/340.

**0.2.16**
- **STOMP limits:** new `maxConnections` (default 10000, per node) and
  `maxSubscribersPerDestination` (default 10000) options, the
  broker-wide counterparts to the per-connection caps added in 0.2.14 —
  see the **Limits** table under STOMP. A full broker refuses a `CONNECT`
  before running `authenticate()`. Full suite: 313/313.

**0.2.15**
- **STOMP:** protocol versions 1.0 and 1.1 are now served alongside 1.2
  (negotiated at `CONNECT`); binary WebSocket frames are accepted inbound;
  new lifecycle hooks `onConnect`/`onDisconnect`/`onSubscribe`/
  `onUnsubscribe`; new `stomp.sendToUser()`, `sendToConnection()` (both
  cluster-aware), `disconnect()` and `getClusterPresence()`; a publish
  now writes to its subscribers concurrently and drops any whose
  connection is found closed; heartbeats run on virtual threads instead of
  one platform thread per connection; and a second `CONNECT` on a
  connected socket is rejected.
- **Bug fix:** `connection.onClose()` never fired when the *server* closed
  the connection (`connection.close()`, including STOMP's fatal `ERROR`s
  and a client `DISCONNECT`), so the STOMP registry entries for those
  connections were never cleaned up. It now fires exactly once per
  connection however it closes. A write that fails or times out now also
  closes the connection and fires it, instead of leaving a zombie.
- New `connection.onBinary(callback)` on `app.ws()` connections.
  `libs/boxexpress-ws-shim-1.0.0.jar` rebuilt. Full suite: 310/310.

**0.2.14**
- **STOMP fixes:** `content-length` is now an octet count, not a
  character count (a multi-byte body used to keep the frame's trailing
  NUL); CRLF-terminated frames now parse (they arrived as command
  `SEND\r` with an empty body); application headers on `SEND` are
  forwarded to subscribers; re-using a subscription `id` on a connection
  is rejected instead of orphaning the first subscription (which kept
  receiving for a destination its owner could no longer unsubscribe); and
  a destination's registry entry is pruned once its last subscriber
  leaves.
- **STOMP limits:** new `connectTimeoutMs`, `maxFramesPerSecond` (opt-in),
  `maxSubscriptions`, `maxTransactions`, `maxTransactionFrames`,
  `maxPendingAcks` and `maxDestinationLength` options, so one client can
  no longer make the broker buffer or wait without bound — see the
  **Limits** table under STOMP. A throwing `authenticate`/`authorize`/
  `onSend` callback now produces an `ERROR` frame instead of silence.
  Full suite: 285/285.

**0.2.13**
- **Security fix:** WebSocket message size is now capped (default 1 MB,
  `app.set( "wsMaxMessageSize", bytes )`). Undertow's own default is
  unlimited, so any `app.ws()` route (including STOMP and the cluster
  relay) would buffer an arbitrarily large message from an
  unauthenticated client (CVE-2026-81624, no upstream fix yet).
  `libs/boxexpress-ws-shim-1.0.0.jar` rebuilt.
- **Security hardening:** response header values are now validated —
  CR/LF, control characters, and characters above Latin-1 are refused,
  because Undertow narrows each character to 8 bits when writing headers
  (CVE-2026-19879, no upstream fix yet), which could turn e.g. U+010A
  into a bare line feed.
- Reviewed the vendored jars: `handlebars-4.3.1.jar` has an advisory
  (GHSA-r4gv-qr8j-p3pg) in `FileTemplateLoader` only, which this project
  never uses (`compileInline` only), so it was left as is. Full suite:
  270/270.

**0.2.12**
- **Bug fix:** a burst of WebSocket clients that connect and then vanish
  mid-write could freeze the entire server, not just those connections —
  root-caused via live reproduction and a thread dump. `app.ws()`'s
  connect callback ran synchronously on Undertow's small, shared I/O
  thread pool (unlike HTTP requests, which already get their own virtual
  thread); a blocking send in that callback against a peer that vanished
  could hang the I/O thread forever, and enough simultaneous zombies
  pinned the whole pool. Fixed by dispatching `onConnect` onto its own
  virtual thread and bounding `connection.send()`'s blocking write with a
  hard 5s timeout. Also fixed a related `Scheduler` bug the same failure
  mode exposed: a hung job body left its overlap flag stuck true forever
  (silently skipping every future tick), and an uncaught exception from
  `taskExecutor.submit()` itself (not just from a job's callback) could
  silently cancel every future tick of that job — see [Scheduler
  support](docs/scheduler.md) for the new watchdog. Verified
  with three consecutive 20-connection burst-and-abandon attacks against
  a live server (zero stalls, continuous ticking throughout, vs. a
  permanent freeze before this fix) plus the full TestBox suite: 268/268.

**0.2.11**
- Bumped the `testbox` devDependency range to `^7.1.0+20` and adopted its
  new assertion features (`assertAll()`, `toBeSameInstanceAs()`,
  `toIncludeAll()`, `withContext()`) in the cluster support test specs —
  grouped failure reporting and labeled context for the timing-sensitive
  assertions that were hardest to debug while building that feature. No
  production code changes; full suite: 268/268.

**0.2.10**
- **Security fix:** bumped the vendored `libs/undertow-core-*.jar` from
  2.4.2.Final to 2.4.3.Final, fixing five CVEs — three on code paths
  this project actually exercises: `CVE-2026-15565` (unauthenticated OOM
  DoS on any WebSocket endpoint with an `@OnMessage` handler),
  `CVE-2026-5680` (OOM DoS via WebSocket `permessage-deflate`
  exponential buffer growth), and `CVE-2026-15561` (missing size/count
  limits on HTTP/1.1 chunked-transfer decoding, unauthenticated OOM
  DoS). Also fixed: `CVE-2026-14180` (a crafted chunk size overlapping
  `ChunkReader`'s own state-flag storage, enabling request smuggling).
  `CVE-2026-15554` (a forged-certificate bypass in Undertow's AJP
  listener) doesn't apply here — this project never uses Undertow's AJP
  listener, only its core HTTP/WebSocket server. New jar's SHA-1
  verified directly against Maven Central's own published checksum
  before vendoring it. `libs/boxexpress-ws-shim-1.0.0.jar` rebuilt
  against the new jar. Full test suite: 268/268.

**0.2.9**
- Added `app.getClusterManager()` — cross-process peer discovery and
  manager election backed by a durable, shared `cache()`, so instances
  never need to know each other's addresses in advance. Two consumers
  built on top of it: `app.schedule(intervalMs, callback, { clustered:
  true })` (only the elected instance runs the tick, with passive
  failover on a missed heartbeat) and `boxExpressStomp({ cluster:
  app.getClusterManager() })` (relays a publish to every other instance
  over a mesh of outbound WebSocket connections, delivered through each
  instance's own subscriber/exchange/listener path — a message that
  arrived via the relay is never relayed back out). Configured via
  `boxlang.json`'s `modules.boxexpress.settings.cluster` (module
  default: off) or `app.set("cluster", {...})`. `cacheProvider` is
  required once enabled, and is validated at startup against BoxLang's
  own `IObjectStore.isDistributed()` — the in-memory default cache is
  rejected outright rather than silently leaving every instance unable
  to see any peer, the same durability requirement
  `boxExpressCacheStore()` already has for sessions. Verified first
  across real separate `boxlang` OS processes (including a live
  leader-kill failover and a cross-process STOMP relay), then written
  up as [tests/specs/ClusterManagerSpec.bx](tests/specs/ClusterManagerSpec.bx),
  [ClusterSchedulerSpec.bx](tests/specs/ClusterSchedulerSpec.bx), and
  [ClusterStompRelaySpec.bx](tests/specs/ClusterStompRelaySpec.bx).
  Deliberately not built: per-job leader affinity (one elected instance
  runs every clustered job, not different jobs on different instances),
  general peer-to-peer RPC, and binary relay payloads. See
  [ClusterManager.bx](models/cluster/ClusterManager.bx) and
  [plans/cluster-support.md](plans/cluster-support.md).

**0.2.8**
- Added `app.schedule(intervalMs, callback, options)` / `Scheduler.bx` —
  fixed-interval recurring jobs, the `setInterval` equivalent
  (deliberately no cron parsing — a real, separable feature this pass
  doesn't build). One shared `ScheduledExecutorService` per app drives
  ticks; each tick's callback runs on its own virtual thread, the same
  request/job-body split `app.listen()` already uses, so a slow job
  never delays another job's tick. No-overlap is the default (a tick is
  skipped, not queued, if the previous run hasn't finished — claimed
  atomically via `AtomicBoolean` so the tick thread and the still-
  running virtual thread can't both think they own the same tick),
  `{ allowOverlap: true }` opts out. A thrown error inside a job is
  caught and logged (`[Scheduler] job 'name' threw: ...`), never left to
  kill future ticks — same posture as `HttpBridge.bx`'s dispatch guard
  and `Stomp.bx`'s server-side listener guard. `app.getScheduledJobs()`
  is a live introspection getter, same pattern as `Stomp.bx`'s
  `getConnections()`/`getSubscriptions()`. `app.close()` cancels every
  job and shuts the scheduler down via the same teardown block that
  already stops the reload watcher and the HTTP server. In-memory only
  (no persistence across restarts), no missed-run catch-up, and no
  clustering — every process instance runs every job independently, the
  same boundary `Stomp.bx`'s docblock draws for itself around multi-node
  clustering. See [Scheduler.bx](models/Scheduler.bx) and
  [tests/specs/SchedulerSpec.bx](tests/specs/SchedulerSpec.bx) (9 specs).

**0.2.6**
- Filled in the STOMP 1.2 gaps identified in a spec-compliance pass over
  `Stomp.bx`/`StompFrameCodec.bx`: `RECEIPT` now sent for any client
  frame carrying a `receipt` header (previously only `DISCONNECT`);
  full `ACK`/`NACK` support (`SUBSCRIBE`'s `ack` header, an `ack` header
  on `MESSAGE` under `client`/`client-individual` modes, cumulative
  acking in `client` mode); `BEGIN`/`COMMIT`/`ABORT` transactions,
  scoped per connection per spec; bidirectional heartbeat negotiation
  (`heart-beat:cx,cy` on `CONNECT`, each direction's interval computed
  independently per spec section 6, plus server-side monitoring that
  closes a connection whose negotiated incoming heartbeats stop
  arriving); and `content-length` honored on both read and write so a
  body with an embedded NUL byte round-trips correctly instead of being
  silently truncated at the first one. `app.ws()`'s WebSocket handshake
  now also negotiates the `v12.stomp`/`v11.stomp`/`v10.stomp`
  `Sec-WebSocket-Protocol` subprotocols, for client libraries (stomp.js
  and others) that send and expect one echoed back — harmless to every
  other `app.ws()` route, confirmed directly: a client that never sends
  that header skips negotiation entirely. See
  [Stomp.bx](models/middleware/Stomp.bx),
  [StompFrameCodec.bx](models/StompFrameCodec.bx), and
  [tests/specs/StompSpec.bx](tests/specs/StompSpec.bx) (20 specs, up
  from 12).
- Followed up with a parity pass against
  [SocketBox](https://github.com/coldbox-modules/SocketBox) (Ortus's own
  WebSocket/STOMP library, read directly from source for this — its
  README and `WebSocketSTOMP.cfc`) to close the remaining real gaps:
  **exchanges/bindings** (`direct`/`topic`/`fanout`/`distribution`, plus
  a custom-exchange escape hatch via a `class` config key), a **live
  connection registry** (`getConnections()`/`getConnectionDetails()`) with
  **`connectionMetadata`** threaded through `authenticate()`/`authorize()`
  and echoed back as `CONNECTED` headers, and **server-side listeners**
  (`options.listeners`) that react to a destination from plain server
  code via a new [StompMessage.bx](models/StompMessage.bx) wrapper
  (auto-JSON-deserializing body, `getConnection()` null for a
  server-originated message). Deliberately still not built: multi-node
  clustering (SocketBox's `ClusterManager`/`ClusterPeer` — a distributed-
  systems feature with no cache-abstraction or multi-node deployment
  story here to build it against) and binary bodies (a transport limit
  of WebSocket text frames, not a broker choice).
  Found and fixed a new, previously-undiscovered BoxLang landmine while
  building this: a function parameter (or local `var`) holding an actual
  `null` can't be referenced by name at all — not read, reassigned, or
  passed through to another function, only `isNull()`-checked — unlike a
  `variables.`-scope member, which tolerates storing and reading back a
  real `null` directly. Worked around with a `""` sentinel converted to a
  real `null` only via a direct `variables.x = javacast("null","")`
  assignment (see `StompMessage.bx`'s `init()`); now documented in
  `docs/ARCHITECTURE.md`'s landmines list alongside a second, smaller one
  (a bare `#` inside a BoxLang string literal needs escaping as `##`,
  which broke `TopicExchange.bx`'s own `#` wildcard match on first
  write). See
  [tests/specs/StompParitySpec.bx](tests/specs/StompParitySpec.bx) (10
  new specs). Full suite: 230/230.

**0.2.4**
- Added `java-src/boxexpress/ws/` — this project's first compiled Java,
  vendored as `libs/boxexpress-ws-shim-1.0.0.jar` (rebuild with
  `java-src/build.sh`). Exists because Undertow's own WebSocket receive
  extension point, `io.undertow.websockets.core.AbstractReceiveListener`,
  is an abstract Java class with protected hook methods, not an
  interface — confirmed directly, not assumed, that BoxLang can't work
  around that: its documented `extends="java:X"` feature for subclassing a
  Java class fails on the installed runtime (a constructor-argument-
  forwarding bug reproduced even with BoxLang's own docs example), and
  implementing the lower-level `org.xnio.ChannelListener` interface
  directly instead hits a wall too — its `handleEvent` fires fine, but the
  only way to actually consume a pending frame,
  `channel.receiveFrame()`, is `protected` and BoxLang's Java interop
  can't invoke it. `BoxWebSocketListener.java` does the real Java-side
  subclassing once and re-exposes every event through a plain interface,
  `WebSocketMessageHandler`, that BoxLang code implements via
  `createDynamicProxy` like everything else in this codebase already does.
  Every callback runs on its own virtual thread, not Undertow's I/O
  thread — confirmed necessary, not assumed, after a version without that
  intermittently reset the connection under a blocking call from inside
  the receive handler, same class of bug already fixed once for the HTTP
  request path. See
  [tests/specs/WebSocketShimSpec.bx](tests/specs/WebSocketShimSpec.bx).
- Added `app.ws(path, callback)` and `WebSocketConnection` — WebSocket
  routes, built on the shim above. A dedicated `WsUpgradeRouter` sits in
  front of Undertow's normal dispatch only when at least one `app.ws()`
  route is registered (an app that never calls it pays nothing extra);
  it delegates a matching `Upgrade: websocket` request to the WebSocket
  handshake handler and everything else — which is almost every request,
  even in an app that does use `app.ws()` — through the exact same HTTP
  dispatch chain, completely unchanged. An upgrade request to a path with
  no registered route falls through to that same chain and gets whatever
  it would otherwise return (a `404` if nothing else matches), rather
  than being silently accepted. `WebSocketConnection`'s `send()`/`close()`
  are safe to call from another thread than the one that opened the
  connection — the same broadcast pattern and the same per-connection
  lock `SseEmitter` already uses, for the same reason.
  `connection.headers`/`connection.cookies` expose the handshake
  request's headers/cookies (mirroring `req.headers`/`req.cookies`), since
  `app.ws()` routes bypass the `use()` middleware chain entirely — a
  session cookie is otherwise unreachable from a WebSocket handler. See
  [WebSocketConnection.bx](models/WebSocketConnection.bx) and
  [tests/specs/WebSocketRouteSpec.bx](tests/specs/WebSocketRouteSpec.bx).
- Added `boxExpressStomp()` / `Stomp` — a STOMP 1.2 pub/sub broker built
  entirely on top of `app.ws()`, no changes needed to the shim or the
  WebSocket routing layer above. `StompFrameCodec.bx` escapes header
  values (backslash/newline/colon) per spec rather than just stripping
  them — the protocol-correct version of the same injection fix
  `SseEmitter.bx` needed, applied here before it could ever become a bug
  instead of after. Deliberately smaller than some STOMP brokers' full
  feature set for a first version — no exchanges/bindings, transactions,
  client `ACK`/`NACK`, binary bodies, or full bidirectional heartbeat
  negotiation; see the [STOMP](docs/stomp.md) docs
  for what's actually built and why the rest was deliberately deferred.
  Two real BoxLang bugs found and fixed while building this, both now
  documented in
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#boxlang-landmines-this-codebase-has-already-found-so-you-dont-have-to):
  a `private` method isn't reachable from a closure once that closure is
  invoked from outside the class that defined it (every helper
  `Stomp.bx`'s handler closures call had to become a plain method
  because of this), and `duplicate()` can't deep-copy a struct holding a
  raw Java object reference — `structCopy()` (shallow) is what
  `_deliver()` actually needs.
  **Security fix (found in review before this shipped):** the shared
  subscriber registry was keyed on the client-supplied `SUBSCRIBE` `id`
  header alone, with no per-connection scoping — since STOMP subscription
  ids aren't secret (many client libraries assign them sequentially,
  e.g. `sub-0`), a second connection subscribing to the same destination
  with the same id silently overwrote the first connection's entry,
  redirecting the first connection's messages to the second with no
  error to either side, and let the second connection's `UNSUBSCRIBE`
  delete the first connection's subscription. Fixed by keying the
  registry on a server-generated per-connection id combined with the
  client's own subscription id, so no cross-connection collision is
  possible even when two clients choose identical ids. See
  [Stomp.bx](models/middleware/Stomp.bx) and
  [tests/specs/StompSpec.bx](tests/specs/StompSpec.bx).
- **Security fix:** `SseEmitter.send()`'s `event`/`id` arguments and
  `comment()`'s `text` argument were concatenated directly into their SSE
  field lines with no newline stripping — unlike `data`, which is safely
  split per-line. An embedded `char(10)` in either let a caller that pipes
  request-derived input into `event`/`id` (a channel name, a correlation
  ID) have a client inject a fabricated `data:`/`event:`/`id:` line, or a
  blank line starting an entirely new, attacker-defined event, into the
  stream every other client reading it sees — a cross-client message-
  spoofing primitive, the same class of issue as CRLF injection in a
  response header value. Found in a security review, not by a report.
  `event`/`id`/`comment()`'s `text` are now stripped of `char(13)`/
  `char(10)` before being written. See
  [SseEmitter.bx](models/SseEmitter.bx).

**0.2.3**
- **Fix:** `res.render()` on BoxLang 1.17.0 was still broken after 0.1.8's
  fix — `app.set("views", dir)` registered the required mapping, but
  `RequestBoxContext.getConfig()` memoizes its merged config struct on
  first read and never invalidates it. The context that runs `app.bxs`
  (captured by `listen()` when it builds `HttpBridge`, then reused for
  every request afterward) may have already read+cached its config via
  an earlier `app.use()`/route call before the mapping was registered —
  so the mapping was live on the runtime's `Configuration` but invisible
  to every request's `include`, and `render()` kept failing exactly as
  before. `app.set("views", dir)` now also calls `getBoxContext().clearConfigCache()`
  right after registering the mapping, forcing that context to pick it
  up. Verified end-to-end against a real running server on BoxLang
  1.17.0+58 — every route serving a view now returns 200 instead of 500.

**0.2.2**
- Added `res.sse(callback)` — Server-Sent Events, a long-lived one-way
  event stream to the client. Not a wrapper around BoxLang's own `SSE()`
  BIF — confirmed directly that it isn't callable from a BoxExpress route
  handler at all (`Function [SSE] not found`), since it lives in the
  `boxlang-web-support` runtime module that BoxExpress doesn't load
  (BoxExpress implements its own HTTP layer directly against Undertow
  instead). `res.sse()`'s `emitter.send()`/`comment()`/`close()`/
  `isClosed()` API deliberately mirrors that BIF's shape anyway, since
  it's a proven design — just built from scratch against Undertow's raw
  exchange. Bypasses the normal buffered `_end()` path entirely (every
  other terminal method builds one byte array and sends it once; this
  writes incrementally as `emitter.send()` is called), and detects a
  client disconnect from a failed write rather than polling connection
  state. `send()`/`comment()`/`close()` are safe to call from more than
  one thread — a route handler broadcasting to an emitter stashed
  elsewhere, concurrently with the connection's own `res.sse()` callback
  — each funnels through the same per-emitter lock so two writers can't
  interleave and corrupt the SSE framing on the shared `OutputStream`;
  confirmed under real concurrent load (5 threads, 125 writes, zero
  malformed/duplicated frames). See [SseEmitter.bx](models/SseEmitter.bx)
  and the [Server-Sent Events](docs/sse.md) docs.

**0.2.1**
- Added `app.getConnectorStatistics()` — live HTTP-layer metrics straight
  from Undertow's own listener (active connections/requests, total request
  count, bytes sent/received, error count, processing time), for a
  server-monitoring dashboard or similar that needs numbers
  `java.lang.management`'s JVM/OS MXBeans can't see. `listen()` now turns
  on `UndertowOptions.ENABLE_STATISTICS` unconditionally (off by default in
  Undertow itself, but the per-request tracking overhead is negligible next
  to everything else already happening per request here) — without it,
  `getConnectorStatistics()` would just return `null` always instead of
  real numbers. See [BoxExpress.bx](models/BoxExpress.bx).

**0.2.0**
- **Breaking:** BoxExpress's server transport is now
  [Undertow](https://undertow.io/) exclusively, replacing the JDK-bundled
  `com.sun.net.httpserver.HttpServer` it ran on before. Verified at full
  behavioral parity against the previous JDK-backed server before the
  switch (the entire test suite passed running for real against either),
  and load-tested with no meaningful throughput/latency difference on the
  routes tested. The pluggable `HttpServerAdapter` seam this module briefly
  carried while both engines existed (an injectable adapter via
  `new BoxExpress(customAdapter)`, plus a `server.engine` setting) has been
  removed now that Undertow is the only engine — `listen()`/`close()` in
  `models/BoxExpress.bx` talk to Undertow directly. `models/adapters/`
  still holds the Undertow-specific wiring classes (exchange wrapping,
  handler glue, the virtual-thread dispatcher), just not as a swappable
  interface.

**0.1.20**
- **Fix:** `Router.bx` reconstructs `req.path` for `app.use()`-mounted
  middleware/sub-routers from segments produced by splitting on `/`,
  which drops empty segments — so a trailing slash on the incoming path
  left no trace once rejoined. Since nearly every request passes through
  at least one `app.use()` layer (even one mounted at root `/`), this
  silently stripped trailing slashes from `req.path` everywhere
  downstream, breaking anything that distinguishes `/foo/` from `/foo` —
  notably `boxExpressStatic()`'s directory-index resolution (`/foo/` →
  `/foo/index.html`). Now reattached from the pre-mutation path string,
  the only place that still has it by the time the remainder is computed.
  See [Router.bx](models/Router.bx).
- Static file serving now mirrors `express.static()`: a request for a
  directory *without* the trailing slash (`/games/void-harvest`) redirects
  (`301`) to the slash-suffixed URL instead of 404ing — the slash version
  is the canonical one, since relative asset links inside the served HTML
  only resolve correctly against it. The redirect target is built from
  `req.originalUrl` rather than `req.path`, since this middleware commonly
  runs after a mount prefix has already been stripped from `req.path` by
  `Router.bx`, but `originalUrl` is captured once at request construction
  and never mutated by that stripping. See
  [StaticFiles.bx](models/middleware/StaticFiles.bx).

**0.1.19**
- Added `resave`/`saveUninitialized` options to `boxExpressSession()`,
  mirroring [express-session](https://github.com/expressjs/session)'s own
  options of the same name. By default this middleware writes to the
  store on every request, even one that never reads or touches
  `req.session` — free against the default in-memory store, a real cost
  against anything out-of-process. `saveUninitialized: false` skips both
  the store write and the `Set-Cookie` for a new session that was never
  modified; `resave: false` skips the store write (cookie still refreshed)
  for an existing session that wasn't modified. A session that *is*
  modified is always saved regardless of either option. Both default to
  `true` (the historical behavior), so this doesn't change anything for
  existing consumers unless they opt in. See
  [Session.bx](models/middleware/Session.bx).
- Added `Response.onBeforeSend(callback)` — registers a callback that runs
  once, synchronously, the instant before headers are actually flushed.
  Needed to implement `saveUninitialized` correctly: whether to send a
  session cookie can depend on what `next()` did to `req.session`, but by
  the time `next()` *returns* to a middleware's own frame, a terminal
  handler further down the chain has normally already called `_end()` and
  flushed the headers — too late to add one there. The same technique
  `express-session` uses via monkey-patching `res.end`, done explicitly
  here since BoxLang can't intercept it that way. See
  [Response.bx](models/Response.bx).

**0.1.18**
- Added `Response.getStatusCode()`/`getBytesWritten()` accessors — expose
  the eventual status code and body byte count from outside the class,
  for request-logging middleware (registered first via `app.use()`) that
  otherwise can't observe a request's outcome. `getBytesWritten()` is `0`
  for a response still in flight; both reflect the real outcome even when
  a route never called `status()` directly, since every terminal method
  that isn't a plain `200` (`redirect()`, `sendStatus()`,
  `sendFileRange()`'s `206`/`304`/`416`) routes through it internally.
  See [Response.bx](models/Response.bx).

**0.1.17**
- **Fix:** `boxExpressSession()` persisted `req.session` to the store
  *before* `next()` ran, not after — so anything the request chain
  mutated on it (`boxExpressCsrf()` minting a token, a login handler
  setting the user, etc.) was written to local memory only and never made
  it back to the store. Invisible against the default in-memory
  `MemoryStore`, since structs are pass-by-reference — that early
  `store.set()` call was really just inserting a reference into a
  `ConcurrentHashMap`, and any later mutation of the same object was
  automatically visible through it. A real out-of-process store has to
  serialize on write (a `boxExpressCacheStore()`-backed `JDBCStore`,
  Redis, etc.), which breaks that accidental reference-sharing and
  exposes the bug for real: a CSRF token minted mid-request never gets
  saved, so the very next request — even the form submission that
  follows the page that minted it — reads back a session with no token
  and `403`s. Now persists after `next()`, wrapped in `try`/`finally` so
  a throw partway through the chain still saves whatever `req.session`
  held at that point; `req.destroySession()` skips that save so a
  destroyed session isn't silently resurrected by it. See
  [Session.bx](models/middleware/Session.bx).

**0.1.16**
- Added `app.set("trust proxy header", "DO-Connecting-IP")` (or an ordered
  array of candidates) for platforms where `X-Forwarded-For` isn't safe to
  trust even with `trust proxy` on — confirmed directly against
  DigitalOcean App Platform (with Cloudflare in front of that) that both
  hops *append* to `X-Forwarded-For` rather than replacing it, so a client
  can prepend a forged entry and have it survive to the app, making
  `req.ip` attacker-controlled rather than just wrong. Platforms that do
  this typically also inject their own edge-set header carrying the real
  client IP (`DO-Connecting-IP`, `CF-Connecting-IP`, `Fastly-Client-IP`,
  etc.), set from the connection the edge itself observed rather than
  anything the client sent — checked before `X-Forwarded-For`, and
  independent of the `trust proxy` boolean, since it's a different, safer
  trust model. See [Request.bx](models/Request.bx).
- Switched `AnsiColor.bx`'s `NO_COLOR` check from
  `createObject("java","java.lang.System").getenv(...)` to the native
  `getSystemSetting(name, defaultValue)` BIF — confirmed directly that it
  reads real environment variables (not just JVM system properties) and
  never throws when a default is given, so the Java interop wasn't
  needed. No behavior change, verified live (`NO_COLOR` still disables
  coloring, a normal run still shows real ANSI codes).

**0.1.15**
- **Fix:** `req.query`/`req.cookies` threw a `500` on a completely ordinary
  input — a query param or cookie with an empty key or value (`?q=`,
  `?=cats`, `Cookie: name=`, `Cookie: =value`). `Request.bx` sliced the
  string with `left()`/`right()` using a count that could legitimately be
  `0`, which BoxLang throws on (`"Count cannot be zero"`) — the exact bug
  class `BodyParsers.bx` already guarded against, just never applied here.
  Along the way, found and fixed a second issue this surfaced: a failure
  constructing `Request`/`Response` was caught silently in
  [HttpBridge.bx](models/HttpBridge.bx) — no server-side log line, and the
  real error message was hidden from the response even with
  `app.set("env", "development")` on, unlike every other error path in the
  framework. It now logs and honors `env` the same way route/middleware
  errors already do.
- **Fix:** a `Range` request header of exactly `bytes=` (a valid header,
  just an empty spec) also threw a `500` — same `left()`/`right()`
  zero-count bug, this time in [RangeParser.bx](models/RangeParser.bx),
  whose own dash-split logic already used `mid()` specifically to avoid
  it. Now falls back to a full `200` response, same as any other
  unparseable `Range` header.
- **Fix:** a sweep of the rest of the codebase for the same
  `left()`/`right()` zero-count bug turned up six more, all in
  [Multipart.bx](models/middleware/Multipart.bx) — the most reachable one
  being a plain HTML file input submitted with nothing selected
  (`filename=""`), which crashed the whole upload instead of the field
  simply being empty, same as it already was for a text field. Also fixed:
  an empty `boundary=` in `Content-Type`, a multipart part with no headers
  or an empty body (a genuinely empty file/field), and a part header with
  an empty name or value. All converted to `mid()`, same fix shape as
  `RangeParser.bx` above.
- **Fix:** a bare `:` path segment (`app.get("/:", ...)`) — a typo for
  `:name` — threw the same `left()`/`right()` zero-count error, but at
  route *registration* time rather than per-request, so it's a developer
  papercut rather than anything reachable by a client. Fixed the same way,
  but also given a proper `BoxExpress.InvalidRoutePath` message (matching
  every sibling validation in [Router.bx](models/Router.bx)) instead of
  leaking a raw `"Count cannot be zero."` engine error with no indication
  of what was actually wrong.
- **Fix:** `listen()` created its underlying `HttpServer` with a TCP accept
  backlog of `0` (the JDK default), which turned out to be a real capacity
  ceiling — confirmed with a live load test (`ab`): request handling itself
  stayed fast and error-free throughout, but new connections started getting
  refused with a reset once concurrency passed roughly 65-70 in repeated
  runs. `listen(port, callback, { backlog: n })` now defaults to `1024`,
  which pushed the same test past 150 with no other change. See
  [BoxExpress.bx](models/BoxExpress.bx).

**0.1.14**
- **Fix + feature:** `app.get(path, ...)` routes now automatically answer
  `HEAD` too — same handler and headers (including `Content-Length`), body
  discarded, same as Express — instead of 404ing. This also fixes a real bug
  in static file serving: it already accepted `HEAD` requests, but sent the
  full file body anyway, a spec violation for a `HEAD` response. Register
  `app.head(path, ...)` explicitly (before the matching `app.get()`) only
  when `HEAD` should behave differently than discarding the `GET` body.
- **Fix:** `app.use()`/`router.use()` only ever accepted a single handler
  (or a single path + single handler), unlike route registration
  (`app.get(path, mw1, mw2, handler)`), which already took a variadic
  chain. Calling `app.use(mw1, mw2, mw3)` — a common Express pattern —
  didn't just silently drop the extra arguments, it threw a confusing
  low-level error (the second handler got mistaken for a mount path).
  `use()` now accepts any number of handlers (and/or a `Router`) in one
  call, at the same mount point, registered in order. See
  [Router.bx](models/Router.bx).
- Added `boxExpressHelmet()` — security-headers middleware mirroring the
  npm [`helmet`](https://github.com/helmetjs/helmet) package's most
  commonly used defaults (`X-Frame-Options`, `X-Content-Type-Options`,
  `Referrer-Policy`, etc.), each individually overridable or disable-able.
  `Strict-Transport-Security` and `Content-Security-Policy` are opt-in
  rather than on by default. See [Helmet.bx](models/middleware/Helmet.bx).
- Added `boxExpressCors()` — Cross-Origin Resource Sharing middleware
  mirroring the npm [`cors`](https://github.com/expressjs/cors) package's
  common options (`origin`, `methods`, `allowedHeaders`, `credentials`,
  `maxAge`). Answers a CORS preflight `OPTIONS` request directly, since
  nothing would otherwise be registered to handle it. See
  [Cors.bx](models/middleware/Cors.bx).
- Added `boxExpressRateLimit()` — fixed-window rate limiting mirroring the
  npm [`express-rate-limit`](https://github.com/express-rate-limit/express-rate-limit)
  package's common options (`windowMs`, `max`, `keyGenerator`), keyed by
  `req.ip` by default. Sets the draft-standard `RateLimit-*` headers and
  responds `429` with `Retry-After` once a key's count exceeds `max`. See
  [RateLimit.bx](models/middleware/RateLimit.bx).
- Added `boxExpressCsrf()` — CSRF protection mirroring the classic
  [`csurf`](https://github.com/expressjs/csurf) package's session-based
  token strategy. Requires `req.session` (register after
  `boxExpressSession()`, which it throws immediately about if missing);
  exposes `req.csrfToken()`, validates `req.body._csrf` (or a request
  header) on every method other than `GET`/`HEAD`/`OPTIONS`. See
  [Csrf.bx](models/middleware/Csrf.bx).
- `boxExpressStatic()`/`res.sendFile()` now accept `options.maxAge`
  (seconds) to set `Cache-Control: public, max-age=<n>` alongside the
  `ETag`/`Last-Modified` they already set — off by default, no header at
  all unless asked for.
- Added `boxExpressCacheStore()` — a `Session` `store` backed by BoxLang's
  own `cache()` service, so `{ store: boxExpressCacheStore( "sessions" ) }`
  makes sessions durable across restarts and shared across a cluster when
  the named cache is configured with `objectStore: "JDBCStore"` (a real SQL
  table) instead of the in-memory default. See
  [CacheStore.bx](models/stores/CacheStore.bx).

**0.1.13**
- Added `Range` request support (RFC 7233) to `res.sendFile()`/`download()`
  and static file serving — a request naming a byte range now gets a `206
  Partial Content` response with just that slice instead of the whole file,
  enabling video/audio scrubbing and resumable downloads. Every file
  response now sets `Accept-Ranges: bytes`. Multi-range requests and
  `If-Range` aren't supported — see [RangeParser.bx](models/RangeParser.bx).
- Added `app.locals`/`res.locals` — two plain structs merged into every
  `res.render()` call's data (`app.locals` < `res.locals` < the explicit
  `data` argument, each overriding the last), so values a route doesn't set
  explicitly don't have to be threaded through every render() call by hand.
- Added `req.protocol`, `req.secure`, and `req.hostname` — same `trust
  proxy` trust model as `req.ip` (`X-Forwarded-Proto`/`X-Forwarded-Host`
  are only honored when `app.set("trust proxy", true)`), since BoxExpress's
  own `HttpServer` never terminates TLS itself.
- Added optional trailing route params (`/users/:id?`) — same restriction
  as the `*` wildcard, only supported as the final path segment, since
  matching one anywhere else would need real backtracking. See
  [Router.bx](models/Router.bx).

**0.1.12**
- **Fix:** `reloadOnChange` could silently kill the server on a failed
  restart. `_restartProcess()` called `close()` unconditionally before
  attempting to launch the replacement process — if that launch failed for
  any reason (a transient OS resource limit, a bad reconstructed command,
  etc.), the exception was uncaught on the watcher's daemon thread, and by
  then the old server was already torn down with nothing left running and
  no clear error tying the two together. Launching the replacement is now
  attempted *before* `close()` releases the port; a failed launch is caught,
  logged in red, and the current server is left running instead of dying
  with it. See [BoxExpress.bx](models/BoxExpress.bx).

**0.1.11**
- Added graceful process shutdown: `listen()` now registers a JVM shutdown
  hook so Ctrl-C/SIGTERM/normal exit runs `close()` (stopping the
  `httpServer` and, if running, the `reloadOnChange` watcher) instead of
  getting torn down mid-flight — previously the watcher's daemon thread
  could outlive a killed server. `close()` is now idempotent, and a bad
  port bind is caught and reported with a friendly message plus `exit(1)`
  instead of a raw stack trace.
- Added `res.dump(data)` — sends BoxLang's HTML `dump()` view of a variable
  as the response, round-tripped through a temp file since
  `dump(format="html")` only ever writes to the process console under
  BoxExpress's CLI-based `HttpServer`.
- Added colored console output (`AnsiColor.bx`): the access log gets a gray
  timestamp, a method colored by verb (`GET`=cyan, `POST`=green,
  `PUT`/`PATCH`=yellow, `DELETE`=red, everything else=magenta), and a dim
  IP; the `reloadOnChange` restart notice is cyan; fatal/error lines are
  red. Respects [`NO_COLOR`](https://no-color.org/) — set it to get plain
  text, e.g. when piping stdout to a file or log aggregator.
- Broadened test coverage: `PUT`/`PATCH`/`DELETE`/`all()` routing,
  `res.header()`/`type()`/`end()`/`sendBytes()`, `req.get()`/
  `rawExchange()`/`ip()` (including the `trust proxy` `X-Forwarded-For`
  case), `app.param()`, `res.redirect()` with an explicit status, and
  `close()` idempotency — plus a new `ProcessLifecycleSpec.bx` covering
  behavior that needs a real OS process boundary (port-conflict exit code,
  SIGTERM releasing the socket, `reloadOnChange` actually replacing the
  process). The test suite's `HttpClient.bx` helper moved from
  `HttpURLConnection` to `java.net.http.HttpClient`, since the former
  hard-rejects `PATCH`.

**0.1.10**
- **Fix:** the router's dispatch loop sometimes called `next()`/`_final()`
  with an explicit `null` error argument on the success path, instead of
  calling them with no argument at all. Express's own contract distinguishes
  the two — `next()` (no args) means "move on successfully," `next(err)`
  means "jump to error handling" — and code downstream that cared about the
  distinction (rather than just checking `isNull(err)`) could misread a
  `null`-argument call as an error. `next()`/`_final()` are now called
  argument-free whenever there's no error, matching Express. See
  [Router.bx](models/Router.bx).

**0.1.9**
- Added `boxExpressRouter()` — a global BIF wrapping `new
  bxModules.boxexpress.models.Router()`, mirroring Express's
  `express.Router()`. Brings `Router` in line with the rest of the public
  API surface, which was otherwise fully reachable through BIFs.

**0.1.8**
- Added `app.set("reloadOnChange", true)` — watches the working directory
  for `.bx`/`.bxs`/`.bxm` changes and restarts the process on save, using
  `java.nio.file.WatchService` and `ProcessHandle.current()` to replay the
  original launch command. See [FileWatcher.bx](models/FileWatcher.bx).

**0.1.7**
- **Fix:** `boxExpressStatic()` threw on any request to the mount root
  (`/`) instead of falling through to `next()`, returning a generic `500`
  for what should have been the app's own route handler. The middleware
  stripped the leading slash with `req.path.right( req.path.len() - 1 )`,
  which becomes `right(0)` for `req.path == "/"` — BoxLang's `right()`
  throws `"Count cannot be zero"` for a zero count (CF/Lucee just returns
  `""`). Switched to `mid()`, which handles it correctly.

**0.1.6**
- Added cookie-based sessions (`boxExpressSession()` / `Session` middleware,
  `req.session`, `req.sessionID`, `req.destroySession()`).

**0.1.5**
- Added `app.param(name, callback)` (Express-style route-param preprocessing),
  `app.route(path)` (chainable per-path route builder), and conditional GET
  support (`ETag`/`Last-Modified`/304) for `res.sendFile()` and static file
  serving.

**0.1.4**
- **Security fix:** the default `500` error handler echoed the raw exception
  message straight back to unauthenticated clients — this could leak
  internal details (absolute server file paths, driver errors, etc.) on any
  unhandled error, reachable via nothing more than a malformed body sent to
  the opt-in JSON parser. The default now sends a generic
  `"Internal Server Error"` message; the real message is still logged to
  stdout, and still returned in the response if the app opts in with
  `app.set("env", "development")`.

**0.1.3**
- **Security fix:** `res.sendFile()`/`res.download()` interpolated a
  caller-supplied `filename` into the `Content-Disposition` header
  unescaped, letting an attacker-controlled value (e.g. passed straight
  through from a query param) break out of the quoted `filename="..."`
  token and inject extra header parameters. Quote and control characters
  are now stripped before the value reaches the header.
- Added file upload (`boxExpressUpload()` / `Multipart` middleware,
  `req.files`) and download (`res.sendFile()`, `res.download()`) support.

**0.1.2**
- `listen()` now blocks the calling thread by default (`options.block`),
  matching Node's effective behavior — no more manual keep-alive loop
  required in consuming apps.
- Added request logging: every request gets a line on stdout as soon as
  it's received.

**0.1.1**
- Added Handlebars (`.hbs`) view rendering alongside the native `.bxm`
  engine.
