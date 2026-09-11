# Cluster support (STOMP relay + Scheduler leader election) — design

**Status: proposed, not built.** Nothing described below exists yet, except
the one item marked confirmed. Written after a feasibility discussion, not a
spec — verify the remaining "Open questions" directly before implementing
any of this, same discipline the rest of this codebase holds itself to (see
`docs/ARCHITECTURE.md`'s "confirmed directly, not assumed" pattern
throughout).

This started as a STOMP-only design (relaying pub/sub messages between
processes) and grew a second consumer once the same gap turned up in
`Scheduler.bx`: both features currently draw the identical boundary around
themselves — "every process instance runs independently, with no awareness
of any other instance" — and both gaps are solved by the same underlying
primitive, peer discovery through a shared cache. One `ClusterManager` per
app, two independent capabilities built on top of it.

## The two problems

**STOMP fan-out.** [Stomp.bx](../models/middleware/Stomp.bx)'s own docblock
already names this: `subscribersByDestination`, `connectionsById`, and
`DistributionExchange`'s round-robin counters are all single-process,
in-memory state. Two clients in the same chat room landing on different
instances behind a load balancer never see each other's messages.

**Scheduler duplicate-run.** [Scheduler.bx](../models/Scheduler.bx)'s
docblock draws the same boundary for a different reason: "no clustering/
distributed coordination — every process instance runs every job
independently." Fine for a job that's supposed to run everywhere (local
cache eviction, say) — wrong for a job that should run exactly *once*
cluster-wide (a nightly digest email, an aggregate rollup). Right now
scaling a BoxExpress app past one instance silently turns every such job
into a duplicate-send bug.

[SocketBox](https://github.com/coldbox-modules/SocketBox) solves its own
version of the first problem with a `ClusterManager`/`ClusterPeer` pair —
cache-backed peer discovery, plus a WebSocket connection *between* server
processes — and happens to already have the second problem's answer built
in too: `ClusterManager.cfc`'s `getManagerNode()`/`isManager()` elect a
single peer as "the manager" using nothing but the same shared cache. That
design (not SocketBox's code, which can't be reused directly — see
[websocket-stomp.md](websocket-stomp.md)'s "Why two layers, not one") is the
reusable part for both consumers here.

## The shared primitive this codebase already has most of

1. **A cache abstraction for shared state.** `models/stores/CacheStore.bx`
   already wraps BoxLang's own `cache()` service behind a `get`/`set`/
   `destroy` contract, durable across restarts and shared across a process
   cluster when the named cache is backed by `JDBCStore` (a real SQL table).
   Both peer discovery *and* manager election need exactly this shape
   (`get`/`set`/`clear` against shared keys) — no new dependency.
   `cache().set()` is a plain overwrite, not a compare-and-set — confirmed
   directly by reading `CacheStore.bx`'s own `set()` — so election below is
   inherently best-effort, the same trade-off `ClusterManager.cfc` itself
   accepts rather than something BoxLang-specific.
2. **`createDynamicProxy` against a plain Java interface**, for the STOMP
   relay's peer-to-peer WebSocket links specifically. **Confirmed directly
   (2026-09-09):** a BoxLang class implementing only `onOpen`/`onText`/
   `onClose`/`onError` (not `onBinary`/`onPing`/`onPong`), proxied against
   `["java.net.http.WebSocket$Listener"]` via `createDynamicProxy`,
   connected through `java.net.http.HttpClient.newWebSocketBuilder()` to a
   live `app.ws()` route and round-tripped a real message —
   `onOpen` → send → `onText` → `sendClose` → `onClose`, all firing
   correctly with only a partial method set implemented. One gotcha found
   along the way: `createDynamicProxy` needs an actual BoxLang **class
   instance**, not a plain struct of closures (`{ onOpen: ()=>{}, ... }`
   fails with `Can't cast Struct to a string`) — matching every existing
   usage elsewhere in this codebase, which already does it the right way.
   This confirms `ClusterPeer.bx` (needed only for the STOMP relay, not for
   leader election — see below) can be built exactly as designed.
3. **BoxLang's own module-settings mechanism**, for config defaults +
   server-level overrides. **Confirmed directly (2026-09-09)**, against
   this project's own `ModuleConfig.bx`/`getModuleInfo()`: a module's
   `configure()` sets a `settings` struct (this project's is currently
   empty); `boxlang.json`'s top-level `modules.<name>.settings` block is
   deep-merged on top of it at module registration — confirmed with a real
   struct nested two levels deep (`cluster.cacheProvider`, etc.): a key
   present only in the module default and absent from the `boxlang.json`
   override survived the merge unchanged, while keys present in both took
   the override's value — a real per-key deep merge, not a full-block
   replace. Reachable at runtime from any BoxLang code via the built-in
   `getModuleInfo( "boxexpress" ).settings` — no custom plumbing needed.
   ([ModuleRecord.java](https://github.com/ortus-boxlang/BoxLang/blob/development/src/main/java/ortus/boxlang/runtime/modules/ModuleRecord.java)'s
   own comment: "Later wins: own configure() defaults < parent module
   overrides < global app config.")

## Architecture

### Cluster config: module defaults, `boxlang.json` overrides, `app.set()` escape hatch

This is genuinely server/ops-level config — which cache backs peer
discovery, this instance's own peer identity, the shared secret — the same
category of thing `sessionStorage`/`caches` already are in a normal BoxLang
deployment, not something that belongs hardcoded into application source
that gets committed to a repo. So it follows the pattern verified above
instead of being purely programmatic:

- **`ModuleConfig.bx`'s `configure()`** declares the defaults (`enabled:
  false`, so installing the module changes nothing for an app that hasn't
  opted in). `cacheProvider` deliberately defaults to `""`, not
  `"default"` — see "a durable cache is required," below, for why leaving
  it unset should fail loudly rather than silently fall back to an
  in-memory cache that can't actually discover anything:

  `allowedObjectStores` is the escape hatch for the durable-store check
  below — a plain array of object-store names `ClusterManager.bx` trusts
  as durable/shared, extendable via `boxlang.json` without a code change
  for anyone plugging in a custom `IObjectStore` (a Redis-backed one, say)
  this project doesn't know about yet:

  ```js
  settings = {
  	cluster: {
  		enabled: false,
  		name: "",
  		cacheProvider: "",
  		allowedObjectStores: [ "JDBCStore" ],
  		peerIdleTimeoutSeconds: 30
  	}
  };
  ```
- **The server's own `boxlang.json`** overrides per-environment, without
  touching application code — the natural place for the durable-cache name
  and the peer's own address, both of which are genuinely different per
  deployment (dev/staging/prod, or even per instance for `name`):

  ```json
  {
  	"modules": {
  		"boxexpress": {
  			"settings": {
  				"cluster": {
  					"enabled": true,
  					"name": "ws://10.0.1.4:3000",
  					"cacheProvider": "clusterPeers"
  				}
  			}
  		}
  	}
  }
  ```
- **`app.set("cluster", {...})` stays available as an explicit override**,
  read on top of whatever `getModuleInfo("boxexpress").settings.cluster`
  returned — needed because this project's own tests and examples
  deliberately construct `BoxExpress` via a relative-path `new` rather than
  the `bxModules.boxexpress` mapping (see the main README's "Hacking on
  BoxExpress itself" section for why), which never registers the module
  with BoxLang's module service at all — `getModuleInfo()` returns an empty
  struct in that case, not an error, so `app.set()` is what lets a test
  or a from-scratch script exercise clustering without needing a
  `boxlang.json` in the mix. Same precedence idiom this codebase already
  uses elsewhere (`app.locals` < `res.locals` < explicit `render()` data):
  module default < `boxlang.json` override < explicit `app.set()`.
- `secretKey` should come from an environment variable either way
  (`"${env.CLUSTER_SECRET}"` in `boxlang.json`, matching the interpolation
  syntax the main README's datasource examples already use) — never
  committed as a literal in either `ModuleConfig.bx` or application source.

#### A durable cache is required — no static `peers` list

Earlier drafts of this design offered a static `peers` array as an
alternative to cache-backed discovery, mirroring `ClusterManager.cfc`'s own
`config.cluster.peers`/cache-provider split. Dropped: a shared cache is the
*only* discovery mechanism now, deliberately, not merely simplified down to
a default.

- **Why:** the whole point of cache-backed discovery is that a node never
  needs to know any other node's address in advance — it registers its own
  identity and reads everyone else's back from the same shared cache. A
  static `peers` list defeats that for exactly the deployments that need
  it most (anything autoscaled, where the instance list changes on its
  own) and, worse, gives this design two discovery code paths to keep in
  sync (`ClusterManager.cfc`'s own `getPeers()` merges both for exactly
  this reason) for a capability nothing here actually needs.
- **`cacheProvider` becomes required whenever `cluster.enabled` is
  `true`.** `ClusterManager.bx`'s constructor validates this and throws
  immediately if it's missing or blank — loud failure at startup, the same
  posture `boxExpressCsrf()` already takes when `req.session` isn't set up
  (see the main README's CSRF section), rather than a cluster that silently
  never discovers any peers.
- **The in-memory-cache trap *is* validatable — confirmed directly
  (2026-09-09), correcting an earlier draft of this doc that assumed
  otherwise.** `cache(cacheProvider).getConfig().properties.objectStore`
  (equally, `cache(cacheProvider).getObjectStore()`'s Java class name)
  exposes the configured store type at runtime — confirmed live against
  the real `"default"` cache, which reports
  `objectStore: "ConcurrentStore"` (BoxLang's in-memory store).
  `ClusterManager.bx`'s constructor checks this against
  `cluster.allowedObjectStores` (default `["JDBCStore"]`) and throws if
  `cacheProvider` resolves to anything not on that list, instead of only
  documenting the risk. This turns "each node silently only ever sees
  itself" from an undetectable silent-failure mode into a startup error
  pointing straight at the misconfigured `boxlang.json`.
- **The allow-list is deliberately about trust, not capability.**
  `cache()` itself is already fully pluggable — any `IObjectStore`
  implementation works through the same `BoxCacheProvider` interface
  `ConcurrentStore`/`JDBCStore` both do, which is exactly why
  `boxExpressCacheStore()` can already sit on top of either without
  caring which one it's talking to. `allowedObjectStores` doesn't affect
  whether a custom store *works* — only whether `ClusterManager` is
  willing to assume it survives a restart or is actually shared across
  processes (a `ConcurrentStore` works fine too, mechanically; it's just
  scoped to one JVM, which is precisely the property that breaks
  discovery). Adding a custom durable store — a Redis-backed
  `IObjectStore`, say — needs nothing from this module beyond listing its
  name in `cluster.allowedObjectStores`; it's already usable with
  `cache()` on its own merits.

### `models/cluster/ClusterManager.bx` — one per app, two capabilities

Lazily created, the same way `BoxExpress.bx` lazily creates its `Scheduler`
— an app that never configures clustering (module default `enabled:
false`, never overridden) pays nothing for it. One instance shared between
both consumers, not one each — see the next section for why that needed a
new public method rather than following automatically from the existing
API shapes.

#### `app.getClusterManager()` — the seam `Stomp` needs and doesn't have today

Found while reviewing this design, not assumed: `Scheduler` and `Stomp` are
constructed completely differently, and the "one shared `ClusterManager`"
framing above only actually works for one of them as things stand.
`app.schedule()` ([BoxExpress.bx:104](../models/BoxExpress.bx#L104)) builds
its `Scheduler` *inside* `BoxExpress.bx` (`new Scheduler(this.settings)`),
so it naturally sees whatever `app.set("cluster", ...)` already stored.
`boxExpressStomp()` ([bifs/boxExpressStomp.bx:18](../bifs/boxExpressStomp.bx#L18))
does the opposite — `new Stomp(options)`, with no `app` reference passed in
at all, and `app.ws("/stomp", stomp.handler())` only wires the two together
*after* `Stomp` already exists. There's no existing seam for `Stomp` to
reach anything living on `app`.

Fix: `BoxExpress.bx` gains a public **`app.getClusterManager()`** —
lazily builds `variables.clusterManager` the same way `schedule()` already
lazily builds `variables.scheduler`, resolving config through
`getModuleInfo("boxexpress").settings.cluster` merged with whatever
`app.set("cluster", ...)` overrode, exactly as designed above. App code
then hands that instance to `Stomp` explicitly:

```js
stomp = boxExpressStomp( { cluster: app.getClusterManager() } )
app.ws( "/stomp", stomp.handler() )
```

`Stomp.bx`'s constructor accepts `options.cluster` as either:

- **omitted / `false`** — no clustering, today's behavior, unchanged.
- **an actual `ClusterManager` instance** — the case above; opens the peer
  mesh (Capability A) on the manager the app already owns, so a Scheduler
  job and the STOMP relay in the same app share one peer-discovery loop,
  one cache-write cadence, one `/__cluster` identity.
- **`true`** — kept as a convenience for a `Stomp` built standalone (a
  script that only ever touches `boxExpressStomp()`, never calls
  `app.getClusterManager()` at all): `Stomp` resolves its own
  module-settings-only `ClusterManager`, with no visibility into any
  `app.set("cluster", ...)` override, since there's no `app` in scope to
  read it from. Documented explicitly as the trade-off of using this shape
  instead of the one above — two independent peer-heartbeat loops if a
  scheduled job in the same process *also* clusters (wasteful, not
  incorrect — heartbeat writes to the same cache key are idempotent
  overwrites either way), rather than something silently different.

Holds:

- **Peer discovery/heartbeat** — writes its own liveness under
  `cluster-peers-<name>` on an interval, reads the shared list back, reaps
  expired entries. Same shape as `ClusterManager.cfc`'s
  `ensureMyselfInCache()`/`reapExpiredCachePeers()`. The tick itself is a
  natural fit for `app.schedule()` — dogfooding this project's own
  recurring-job primitive instead of hand-rolling another thread loop
  (with the obvious wrinkle that the *scheduler's own* cluster-awareness,
  below, must not apply to this internal job — it has to run on every
  instance, not just the elected manager, or peer discovery itself would
  stop working the moment there's more than one node).
- **Capability A — the peer WebSocket mesh**, only opened if the STOMP
  broker asks for relay. A map of currently-open `ClusterPeer` connections,
  reconciled on the same tick: connect to any peer in the shared list not
  yet connected, drop any no longer listed or whose socket died. This is
  the only part that needs `models/cluster/ClusterPeer.bx` and the
  `/__cluster` receiving route — an app using only clustered scheduling and
  no STOMP broker never opens a single peer socket.
- **Capability B — manager election**, `isManager()`/`getManagerNode()`,
  needed only by the Scheduler. Pure cache read/write, no WebSocket
  involved at all: read the `cluster-manager-<name>` key; if it's empty, or
  names a peer that isn't currently live in the discovery list, claim it by
  writing this instance's own name. Racy by construction (no CAS — see
  above) but self-correcting, since every node re-checks on its own next
  tick and a stale claim gets overwritten once its writer's heartbeat
  expires.

### Consumer 1: STOMP message relay

- `boxExpressStomp({ cluster: app.getClusterManager() })` — passing the
  app's shared manager (see `app.getClusterManager()` above) opens the
  peer mesh (Capability A) on it. `{ cluster: true }` also works
  standalone, at the cost of not sharing a peer-discovery loop with any
  clustered `app.schedule()` job in the same process.
- **Relay flow**, unchanged from the original design: `_deliver()`
  ([Stomp.bx:690](../models/middleware/Stomp.bx#L690)) — after delivering
  to local subscribers/listeners as it does today — hands
  `{ destination, body, headers }` to `ClusterManager.relay()`, which calls
  `sendText()` on every open `ClusterPeer`. The receiving instance's
  `/__cluster` handler deserializes that envelope and calls the *same*
  `_deliver(destination, body, headers, originPeer=senderName)` its own
  local clients use — same delivery path, no parallel one to maintain.
- **Loop guard:** `_deliver()` gains an `originPeer` parameter (mirroring
  the existing `originConnection` parameter already threaded through for
  `StompMessage.getConnection()`). Non-empty means "this arrived from a
  peer" — skip relaying it back out. Flat one-hop flood, not a spanning
  tree, matching SocketBox's own flat peer mesh.
- **Handshake auth for `/__cluster`:** checked against `connection.headers`
  (the shared `secretKey`) before treating any frame as a trusted relay
  envelope — an unauthenticated endpoint would let anyone who can reach the
  port inject fabricated broadcasts into every subscriber on every node.
- Guarantees unchanged from the original design: no cross-node ordering
  beyond STOMP's existing single-node guarantees, fire-and-forget (a peer
  down when a message sends means that peer's subscribers miss it, no
  retry/queue), `getConnections()`/`getSubscriptions()` stay per-node.

### Consumer 2: Scheduler leader election

- **`app.schedule(intervalMs, callback, options)` gains
  `options.clustered`** (default `false` — every existing job keeps
  running on every instance exactly as it does today; this is opt-in, not
  a behavior change for anyone not asking for it, same posture every other
  option on this method already has).
- **`Scheduler.bx` gains an optional `clusterManager` reference**, passed
  in from `BoxExpress.bx` the same way `Stomp.bx` gets one — only if
  `app.set("cluster", ...)` was called. An app with no cluster config
  passes `clustered: true` to `schedule()` and gets... exactly today's
  behavior, since there's no manager to check against. That's a silent
  no-op rather than a thrown error deliberately — flag it in the docs, but
  don't make "I set clustered:true without configuring clustering" a hard
  failure over a likely-harmless config-ordering mistake.
- **`_runTick()` gains one guard, before anything else in the method:**

  ```js
  if ( job.clustered && !isNull( variables.clusterManager ) && !variables.clusterManager.isManager() ) {
      return   // not the elected leader for this tick — do nothing at all,
               // not even the overlap-guard bookkeeping below
  }
  ```

  A non-leader node does zero work for a clustered job's tick — it doesn't
  touch the per-job `AtomicBoolean`, doesn't submit anything to the
  virtual-thread executor. Only the elected manager's `Scheduler` instance
  ever actually runs the callback.
- **Failover is passive, reusing the peer-discovery machinery Capability A
  already needs for STOMP** (or, for a scheduling-only app, the same
  machinery running with no peer WebSocket mesh at all — see above): if the
  leader dies, its heartbeat key in the shared cache goes stale past
  `peerIdleTimeoutSeconds`. The next node to call `isManager()` — on its
  own already-running tick, no separate reconciliation thread needed —
  sees the old manager is gone and promotes itself. Bounded gap: a
  clustered job can go unrun for up to roughly one tick interval plus the
  peer-expiry window after a leader crash. Documented explicitly as a
  known limitation, the same honesty `Scheduler.bx` already applies to
  "no missed-run catch-up."
- **This is at-most-almost-once, not exactly-once.** No CAS on the cache
  write means two nodes can both briefly believe they're the manager in
  the narrow window right after a failover, and both run one tick of a
  clustered job before the next check reconciles. Fine for a job that's
  naturally idempotent (a rollup that overwrites the same row) or where an
  occasional double-run is a shrug, not an incident. **Explicitly not fine
  for anything where a double-run is unacceptable** (charging a customer,
  sending a one-time notification with real consequences) — the docs for
  `options.clustered` need to say this plainly rather than let it be
  discovered the hard way, the same way `allowOverlap`'s docs are upfront
  about its own trade-off today.

## Deployment shape

Ops-owned config in the server's `boxlang.json` (per-environment, no code
change to promote dev → staging → prod other than this file):

```json
{
	"modules": {
		"boxexpress": {
			"settings": {
				"cluster": {
					"enabled": true,
					"name": "ws://10.0.1.4:3000",
					"cacheProvider": "clusterPeers",
					"secretKey": "${env.CLUSTER_SECRET}"
				}
			}
		}
	}
}
```

Application code just uses the features — no cluster config duplicated
here, it was already resolved from `boxlang.json` (or `app.set("cluster",
...)`, for a test/script that bypasses module registration):

```js
stomp = boxExpressStomp( { cluster: app.getClusterManager() } )   // shares one peer-discovery loop with the job below
app.ws( "/stomp", stomp.handler() )

app.schedule( 3600000, sendNightlyDigest, { clustered: true, name: "nightly-digest" } )
app.schedule( 60000, cleanupLocalTempFiles )   // unclustered — still runs everywhere, unchanged
```

An app can use either consumer independently: clustered scheduling with no
STOMP broker at all never opens a peer WebSocket; a STOMP relay with no
clustered jobs never calls `isManager()`. An app that never sets
`cluster.enabled` (the module default) pays nothing for any of this.

## Non-goals for v1

- **No general-purpose peer RPC** (SocketBox's `RPCRequest`/
  `onRPCResponse`, arbitrary request/response between nodes). Manager
  election is now in scope because the Scheduler needs it, but that's the
  one specific thing built — not a generic peer-messaging API.
- **No per-job leader affinity/sharding.** v1 election is all-or-nothing:
  one elected node runs *every* clustered job on the app. Spreading
  different clustered jobs across different nodes for load distribution
  (each job independently claiming its own lock rather than following one
  global manager) is a real future enhancement, not this pass.
- **No binary cluster payloads** — the STOMP relay envelope is JSON text,
  consistent with STOMP's own textual framing here and the text-only
  transport limit `boxexpress-ws-shim` already has.
- **No cross-node presence/registry merging.** `getConnections()`/
  `getScheduledJobs()` both stay per-node — a cluster-wide view is an
  aggregation problem for whatever's consuming those getters.
- **No delivery or execution guarantees across a crash.** A STOMP message
  in flight when a peer drops is lost to that peer; a clustered job tick
  during a leader failover may run zero or (briefly) two times. Both
  match this project's existing no-queue, no-redelivery, no-catch-up
  posture rather than introducing new promises this design can't actually
  keep.

## Landmines to watch for while implementing

Drawn from this codebase's own documented history
(`docs/ARCHITECTURE.md`'s landmines list, the changelog entries that found
them) rather than speculation — each of these has already bitten this
project once in a similar shape:

- **`structCopy()`, not `duplicate()`, for the peer-connection map.**
  `ClusterManager`'s map of currently-open `ClusterPeer` instances holds
  Java WebSocket references, the exact shape `_deliverToDestination()`'s
  own comment in `Stomp.bx` already warns about — `duplicate()` tries to
  deep-copy every value and doesn't know how to duplicate a raw Java
  object, so any snapshot-then-iterate pattern (reconciling the peer list
  outside a lock, say) needs the shallow copy instead.
- **A `private` method isn't reachable from a closure once that closure
  runs outside the class that defined it.** The exact bug the 0.2.4
  changelog hit once building `Stomp.bx` — every helper its handler
  closures called had to become a plain method because of it.
  `ClusterPeer`'s `onOpen`/`onText`/`onClose`/`onError` (invoked by the
  JDK's WebSocket machinery, well outside `ClusterPeer`'s own call stack)
  need to be plain methods if any of them calls a shared helper.
- **Don't name a local variable or parameter `cache` inside
  `ClusterManager.bx`.** It calls the `cache()` BIF constantly
  (`cache(cacheProvider).set(...)`, the durable-store check, etc.) — a
  same-named local would shadow the BIF silently rather than erroring,
  the same class of pitfall the main README's reserved-scope-names list
  documents for `server`/`session`/`request`/etc., just against a
  built-in function instead of a scope.
- **`left()`/`right()` with a computed count of zero throws `"Count
  cannot be zero"`** — a recurring bug class in this codebase (the 0.1.15
  changelog found and fixed six unrelated instances of it in one sweep).
  Worth a specific check wherever `ClusterManager`/`ClusterPeer` slice a
  peer name or URI (stripping a `ws://` prefix, say) — prefer `mid()`,
  the fix this codebase already standardized on everywhere else.

## Open questions / risks to validate before building

- ~~Does BoxLang's `createDynamicProxy` accept
  `java.net.http.WebSocket$Listener`?~~ **Confirmed directly (2026-09-09)**
  — see above. `ClusterPeer.bx` can be built as designed.
- ~~Does `boxlang.json`'s `modules.<name>.settings` actually deep-merge
  onto `ModuleConfig.bx`'s own `configure()` defaults, and is
  `getModuleInfo("boxexpress")` the right way to read the result at
  runtime?~~ **Confirmed directly (2026-09-09)**, including that the
  registered module name is `"boxexpress"` (matching `this.mapping` and
  the `boxlang_modules/boxexpress/` directory, not `box.json`'s `name` or
  `slug` fields) — see above. Also confirmed: `getModuleInfo()` for a
  module BoxLang's module service never registered (this project's own
  tests/examples, which use a relative-path `new` specifically to avoid
  that registration) returns an empty struct rather than throwing, which
  is exactly why `app.set("cluster", ...)` needs to stay as a working
  fallback rather than becoming redundant once module settings exist.
- **Does BoxLang's `cache()` service support a per-entry TTL the same way
  `CacheStore.set(id, data, maxAge)` implies for sessions?** Needed so a
  crashed node's manager/peer-heartbeat entries expire on their own rather
  than relying solely on the read-time staleness check every other node
  already does — belt and suspenders, not strictly required since the
  staleness check alone (comparing a stored timestamp) is what
  `ClusterManager.cfc` actually relies on, but worth confirming which
  mechanism this codebase ends up leaning on.
- **Self-connect guard.** A node must never add itself as a peer
  (`peerName == myPeerName`, straight out of `ClusterManager.cfc`) — for
  the relay mesh, to avoid a message relaying to itself; for election, so
  a lone node correctly and immediately elects itself manager rather than
  waiting on a peer list that will never include anyone else.
- **`app.schedule()`'s own internal peer-discovery tick must never be
  clustered** — it has to run on every instance to do its job (each node
  reporting its own liveness). Worth a code comment at the call site, not
  just this doc, so a future edit doesn't "helpfully" add
  `clustered: true` to it.
- **Reconnect/backoff for a dropped relay peer** — `ClusterManager.cfc`
  handles this with its own periodic `checkPeers()` reconciliation, which
  this design drives via `app.schedule()` instead of a bespoke thread.
  Confirm a job scheduled from inside a lazily-constructed
  `ClusterManager` tears down cleanly on `app.close()`, through the same
  `Scheduler.shutdown()` path everything else already goes through.
- **How aggressively does `isManager()` get called?** Every clustered
  job's every tick calls it in the current design — cheap (one cache read,
  usually) but worth confirming it isn't a bottleneck for an app with many
  clustered jobs on a short interval; caching the answer for a second or
  two (accepting slightly staler failover detection in exchange) is a
  reasonable tweak if it turns out to matter, not a redesign.
