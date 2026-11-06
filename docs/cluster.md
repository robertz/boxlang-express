# Cluster support (`app.getClusterManager`)

`app.getClusterManager()` lazily builds one shared `ClusterManager` for
the app — cross-process peer discovery and manager election, backed by
a durable, shared `cache()`, so instances never need to know each
other's addresses in advance: each one registers its own identity and
reads everyone else's back from the same cache. Two independent
consumers are built on top of it:

- **`app.schedule(intervalMs, callback, { clustered: true })`** — see
  [Scheduler](scheduler.md). Only the elected instance
  actually runs a clustered job's tick; every other instance does
  nothing for it, not even the overlap bookkeeping a normal job gets.
  Failover is passive: if the elected instance goes down, the next
  instance to check discovers its heartbeat has gone stale and a new
  manager is chosen, within roughly one heartbeat cycle. See
  [How the manager is elected](#how-the-manager-is-elected) below.
- **`boxExpressStomp({ cluster: app.getClusterManager() })`** — see
  [STOMP](stomp.md). Opens a mesh of
  outbound WebSocket connections to every live peer; a `SEND`/
  `stomp.send()` that would otherwise only reach local subscribers is
  also relayed to every other instance, which delivers it to *its own*
  local subscribers through the exact same subscriber/exchange/listener
  path a real client's `SEND` uses. A message that arrives via the
  relay is never relayed back out, so it can't loop.

An app that never opts in (`cluster.enabled` stays `false`, the
default) pays nothing for any of this — every method on
`ClusterManager` becomes a no-op that matches today's unclustered
behavior exactly (a clustered job runs on every instance, same as an
unclustered one).

**Configuration**, in increasing precedence — module default (this
module ships with clustering off) → `boxlang.json` → `app.set("cluster",
{...})`:

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

`cacheProvider` is required once `enabled` is `true`, and must name a
cache backed by a genuinely durable/shared object store — validated at
startup via BoxLang's own `IObjectStore.isDistributed()` (true for
`JDBCStore`, false for the in-memory `ConcurrentStore` default), not
just documented as a risk. A store that reports `isDistributed() ==
false` can still be allowed explicitly via `allowedObjectStores`.
`secretKey` gates the relay mesh's `/__cluster` endpoint and is required
(at least 16 characters) whenever a STOMP broker is given
`{ cluster: ... }` — the endpoint is on the app's public port, and
anything that connects to it can publish to every subscriber in the
cluster. It's compared in constant time. Every instance must share the
same value, sourced from an environment variable, never a literal in
source. The secret travels in a handshake header over `ws://`, so keep
peer traffic on a private network. Clustering used only for
`app.schedule({ clustered: true })` doesn't open the endpoint and doesn't
need it. `peerIdleTimeoutSeconds` (default
`30`) controls how long a missed heartbeat is tolerated before a peer
is considered gone.

## How the manager is elected

The shared cache has no compare-and-set, so election isn't "first
instance to write the manager key wins" — two instances that both found
it empty would both win. Instead:

- An instance becomes an **election candidate** the first time it checks
  who the manager is (a clustered job's tick does this), and says so on
  its heartbeat. An instance with no clustered jobs is never elected.
- A manager whose heartbeat is live **keeps the role**; a new instance
  joining doesn't take it over. The manager renews its claim on every
  check and on every heartbeat, so the role doesn't lapse while it's
  healthy, even if its clustered jobs run less often than the claim's
  expiry.
- With no live manager, every instance picks the **same winner from the
  same heartbeat data** — the live candidate with the lowest name
  (case-sensitive) — and only the winner writes the claim.

Two instances can only both act as manager if they disagree about who is
live, which happens briefly as a peer's heartbeat crosses
`peerIdleTimeoutSeconds`. That makes a double-run rare, not impossible:
a clustered job is at-most-almost-once, so don't use one where a
double-run is unacceptable (charging a customer, a one-time notification
that matters). Heartbeats are written every third of
`peerIdleTimeoutSeconds` (1s minimum).

`app.getClusterManager().getClusterMembers()` returns every live peer
plus this instance's own name — the "who's actually in the cluster"
view for a status endpoint or dashboard.

Explicit non-goals for this first version: **no per-job leader
affinity** (one elected instance runs *every* clustered job in the
app, not different jobs on different instances), **no general
peer-to-peer RPC** (the relay only ever carries a STOMP publish), and
**no binary relay payloads** (the envelope is JSON text, matching
STOMP's own transport limits here). See
[ClusterManager.bx](../models/cluster/ClusterManager.bx) and
[plans/cluster-support.md](../plans/cluster-support.md) for the full
design.
