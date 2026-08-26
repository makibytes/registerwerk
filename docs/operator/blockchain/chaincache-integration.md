---
title: chaincache Integration
---

# chaincache Integration

[chaincache](https://github.com/makibytes/chaincache) is a sibling product: an RPC gateway with its
own canonical-chain/finality tracking. Registerwerk can connect to a chaincache instance the same
way it connects to any other RPC node — as a URL in the node list — but when it does, it gets
push-based, gap-free reorg retraction and a real `SAFE` tier instead of the coarse, poll-based
finality a direct node connection provides. This page describes what that upgrade actually is, and
the (deliberately minimal) configuration it takes.

## One chaincache workload per chain

chaincache's deployment model is **one Spring Boot process per chain**, not one instance serving
every chain — `chaincache.runtime.allow-multiple-chains: false` is its own default, and it refuses
to start with zero or more than one configured chain. "Multi-chain" means N workloads, each its own
deployable, each fronting exactly one chain. Shared infrastructure — PostgreSQL and monitoring —
ingress — is designed to be shared *across* those workloads: every row a workload writes is
chain-scoped, so two workloads can point at one Postgres instance without colliding. The demo stack
runs this for real: `chaincache-sepolia` and `chaincache-base` are two independent containers
sharing the `chaincache` database on Registerwerk's own `postgres` service — not a dedicated
Chaincache Postgres container.

This changes what "connecting Registerwerk to chaincache" means at the network level: there is no
single chaincache hostname to point every chain's node at. Each `RpcNode` of kind `CHAINCACHE`
points at the specific workload that serves *its* chain — `managementUrl` is that workload's own
host:port, and `remoteChainKey` is the chain key that workload was configured with (almost always
the only chain key it knows, since each workload serves one chain).

The PostgreSQL durable event log is Chaincache's sole authoritative delivery source, exposed
through the acknowledged lifecycle WebSocket. Registerwerk uses the JSON-RPC/web3j proxy
(`/{chain}/rpc`) and that WebSocket (`/{chain}/ws`); there is no second, broker-mediated path whose
schema, ordering, or recovery behavior could diverge.

## Why this is a node kind, not a config file

An earlier design treated chaincache adoption as "zero Registerwerk code change — it's a URL in a
table," on the theory that invisibility was the point. For a product whose purpose includes
demonstrating what a registry gains from chaincache, invisibility is the opposite of the point:
`RpcNode` has a `kind` (`DIRECT_RPC` | `CHAINCACHE`), and the operator UI states, per connection,
which guarantees it actually provides. A direct RPC node gives poll-based, coarse finality that can
miss a short-lived reorg and has no `SAFE` tier without a `safe` block tag; a chaincache connection
additionally exposes push-based, gap-free, replayable retractions and a real `SAFE` tier.

## Auto-detection — there is nothing to configure by hand

An operator adds a node the same way regardless of what it turns out to be: paste a URL, optional
label. Whether that URL is a chaincache connection is auto-detected, not declared:

1. The URL is checked against chaincache's routing convention, `/<chainKey>/rpc` — a URL shaped
   that way yields a candidate `managementUrl` (scheme+host+port) and `remoteChainKey` (the last
   path segment).
2. The candidate is verified with a live `GET <managementUrl>/api/capabilities` call, checked for
   an entry whose `chainKey` matches. Only on a match is the node actually recorded as `CHAINCACHE`
   — a URL that merely *looks* chaincache-shaped but doesn't answer as one (a coincidence, a real
   third-party RPC endpoint whose path happens to end `.../rpc`, or chaincache being temporarily
   down) is treated as `DIRECT_RPC`, not left in an "unknown" state. A confirmed match is followed
   by a second, best-effort `GET <managementUrl>/api/chains` call for that workload's upstream
   provider counts (see [What gets probed and shown](#what-gets-probed-and-shown)) — a failure there
   degrades to missing node counts, it never undoes the confirmed match.
3. A background job re-runs this check for every enabled node roughly once a minute, in both
   directions — a `DIRECT_RPC` node whose URL starts answering as chaincache gets promoted; a node
   that fails to reach chaincache three consecutive times, or is reachable but no longer lists the
   expected `remoteChainKey`, falls back to `DIRECT_RPC`. That three-strike hysteresis (immediate on
   a confirmed "no longer serves this chain", tolerant of a lone transient blip) exists specifically
   so one dropped probe doesn't tear down a chain's durable-event stream. A manual "re-check now"
   action exists per node for an operator who doesn't want to wait for the next tick.
4. A URL that resolves to an authority which just failed a probe is skipped for an hour (a negative
   cache) rather than re-probed every tick — this is what keeps a real third-party RPC endpoint that
   happens to match the `/<key>/rpc` shape from being hit with a repeated, noisy outbound probe.

There is no kind selector anywhere in the product — an operator cannot declare a node's kind, only
find out what it is.

`ChainConfig.finalitySource` (`RPC_SELF_PROBE` | `CHAINCACHE`) follows the same principle: it is
recomputed automatically from whether the chain currently has an enabled `CHAINCACHE`-kind node,
every time a node is added, removed, enabled, disabled, or re-detected. There is no field for an
operator to set it directly either.

## Authentication

chaincache's own default is `chaincache.auth.enabled: true`, requiring a bearer token with role
`USER`/`ADMIN`/`OPERATOR` on `/api/**` and `/{chain}/api/**`. The standard RPC/WebSocket handshake
may stay open under the default `chaincache.auth.rpc-enabled: false`, but durable methods still
require an authenticated principal because they mutate named replay cursors
(`chaincache.auth.durable-required: true`). Registerwerk therefore mints its own short-lived HS256 token
(`chain.internal.ChaincacheTokenFactory`, a 5-minute token carrying `roles: ["OPERATOR"]`, cached
until shortly before expiry) from `registerwerk.chaincache.jwt-secret` — a secret **distinct from**
`JWT_DEV_SECRET`; never reuse Registerwerk's own operator-login signing key here, since anyone
holding a chaincache credential would then also be able to forge Registerwerk operator sessions.
That secret must match the target workload's own `chaincache.jwt.secret`.

If the secret is missing or wrong, capability probes and durable-stream connections fail with a
401/403. This is deliberately **not** treated as "not a chaincache instance" — a node that answers
with 401/403 is left exactly as it was (a confirmed `CHAINCACHE` node stays `CHAINCACHE`, a
newly-added node stays `DIRECT_RPC`), and one `WARN` log names the fix
(`registerwerk.chaincache.jwt-secret`) rather than repeating it every probe tick. A bad or missing
secret can never silently rewrite the data model.

`registerwerk_chaincache_capability_probe_failures_total{management_url,reason}` distinguishes this
case (`reason="unauthorized"`) from `chain_missing` (reachable, doesn't serve this chain) and
`unreachable` (network/timeout/5xx) — see [Observability](#observability) below.

## What gets probed and shown

Once detected, `GET /api/capabilities` is re-probed on the same schedule as kind re-detection, and
its response is stored as the node's `capabilities`: finality model, safe/finalized confirmation
depths, which RPC namespaces are configured, whether `debug_traceBlockByHash` is actually available
upstream, durable-stream availability, and a stable `durabilityDomainId`. The domain ID identifies
the backing PostgreSQL outbox/cursor database; Registerwerk
refuses automatic failover unless every enabled candidate for a chain reports the same nonblank
value, because an equal chain key does not make two independent event stores cursor-compatible. A confirmed
match additionally folds in `GET /api/chains`' per-workload upstream provider counts
(`configuredNodeCount`/`availableNodeCount` — how many upstream RPC providers that workload has
configured for the chain, and how many currently answer). The operator node list's expandable panel
on a chaincache-kind row shows all of this — plus which workload (`managementUrl` +
`remoteChainKey`) actually serves the chain, and whether Registerwerk currently has a live
durable-event connection open to it — directly next to a direct node's plainer health data; the
capability gap is deliberate and visible, not hidden.

## The durable stream

When a chain's `finalitySource` is `CHAINCACHE`, `blockchain.internal.ChaincacheDurableStreamManager`
opens a persistent WebSocket to `<managementUrl>/<remoteChainKey>/ws` and issues
`chaincache_subscribeLifecycle` with a `consumerId` of the form
`registerwerk:<instanceId>:<remoteChainKey>` — stable across restarts of the same Registerwerk
deployment. Every replica deliberately uses that same logical ID: chaincache's cross-replica,
PostgreSQL-backed lease elects exactly one active connection while the others retry as warm
standbys. This preserves one cursor through rolling updates without splitting the stream or
deriving durable identity from an ephemeral pod name. **There is no separate resume call to make**:
chaincache persists each
consumer's unified lifecycle cursor server-side (`durable_consumer_cursor`, keyed by
`consumerId`) and resumes it automatically when that identity subscribes again.

Each durable event carries a chain-local sequence, deterministic event ID, finality stream, and
kind. `BLOCK`, `LOG`, finality promotions, reorgs, and retractions share one delivery order and one
ACK cursor. A fork first produces one typed `REORG` event whose `reorg` object is a
versioned V1 envelope: occurrence ID, common ancestor, ordered orphaned/replacement lineages,
severity (`ROUTINE`, `UNRESOLVED_ANCESTRY`, or `FINALITY_VIOLATION`), and observation time. Legacy
per-event `RETRACTION`s follow for compatibility. Registerwerk applies a typed routine episode as
one ordered compensation saga in reverse journal order, deduplicates the following legacy
retractions by episode ID, and acknowledges only after the whole local transaction succeeds. A
failed compensation quarantines the affected chain scope and **does** acknowledge the event —
recording `INDEXER_COMPENSATION_FAILED` and moving on, rather than leaving the cursor stuck behind
a poison event it will only ever fail to apply the same way again on every retry. Unresolved
ancestry or a finalized-history violation is quarantined immediately and never applied as an
ordinary rollback. This is what makes detection and correction gap-free and push-based: a missed
event is replayed on reconnect rather than silently lost.

A **malformed** lifecycle event (a payload-hash conflict on a reused `eventId`, or a lifecycle
sequence gap/regression against this consumer's own cursor) is different from a resolved reorg
episode: it quarantines both `chaincache_event_inbox` (that specific event) and
`chain_contract_subscription` (this consumer, chain-wide) and does **not** acknowledge — the
connection fail-stops (WebSocket close code 1011) so a later sequence can never acknowledge past
it. This wedges the durable stream until an operator explicitly recovers it: the same
`POST /api/v1/finality-journal/chains/{chainConfigId}/resolve-quarantine` action used to lift a
chain-level quarantine also clears any quarantined inbox/subscription rows for that chain (see
`GET .../chains/{chainConfigId}/quarantined-events` to review what will be cleared first) — the
durable stream manager's next reconnect then resumes normal processing. A benign redelivery of an
event this consumer already durably processed (a lease takeover mid-batch, or Chaincache's own
cursor restore) is handled separately and does not wedge anything: it is acknowledged as a no-op
without re-applying its effect or moving the cursor backwards.

Chaincache prunes `durable_event` rows older than `chaincache.events.retention-max-age` (default
30 days) once every known lifecycle consumer's acknowledged position has moved past them — never
past the oldest still-unacknowledged position, so a healthy, merely-slow-to-reconnect consumer's
history is never pruned out from under it. A consumer whose resolved replay position (its own
persisted cursor takes priority over the `startBlock` Registerwerk supplies on every connect — see
"The durable stream" above) falls at or below the resulting retention floor is refused with a
distinct error at subscribe time instead of an ordinary forward gap, and
`GET /{remoteChainKey}/api/durable/stats`'s `retentionFloorSequence` exposes the current boundary.
Unlike a quarantine, this is **not** something `resolve-quarantine` recovers from: Registerwerk's
stable `consumerId` (`registerwerk:<instanceId>:<remoteChainKey>`) keeps retrying the same rejected
resume point on every `reconcile()` tick. Recovering requires an explicit operator decision — most
directly, deleting that consumer's `durable_consumer_cursor` row on the Chaincache side so its next
subscribe falls back to Registerwerk's supplied `startBlock` (a bounded resync from the earliest
deployment block, not a silent one) — which is exactly why the target Chaincache workload's own
`chaincache.events.retention-max-age` should comfortably exceed the longest Registerwerk-side
outage this deployment is willing to tolerate without a manual resync, and why
`registerwerk_chaincache_stream_last_event_timestamp_seconds` (see
Observability below) is worth alerting on well before a stalled connection could approach it.

The plain-RPC reorg-detection path (`ReorgGuard`, described in
[Indexer Resilience](../indexers/resilience.md)) is untouched and still runs for every
`RPC_SELF_PROBE` chain — the durable stream is an additional, higher-fidelity signal for chains
that have opted into it, not a replacement for chains that haven't. A `CHAINCACHE` chain also gets
its finality-window re-verification served from chaincache's own
`GET /{remoteChainKey}/api/blocks/{number}/finality` endpoint instead of RPC polling — any failure
there (404, 5xx, timeout, 401) falls back to RPC self-probing rather than manufacturing a false
reorg, so chaincache being temporarily unreachable degrades gracefully instead of breaking finality
tracking outright.

## One-command demo deployment

Chaincache stays an independently versioned product and image. Registerwerk owns only the local
orchestration: there is no `../chaincache` build context and no second Compose project to start.

1. Make the desired image available to Docker by pulling it, loading an image archive, or building
   it in the Chaincache repository.
2. Set its exact tag as `CHAINCACHE_IMAGE` and set `CHAINCACHE_ENABLED=true` in Registerwerk's
   `.env`. Keep `chaincache-true` in `COMPOSE_PROFILES`; the supplied example files already do.
3. Run the normal `docker compose up -d` from Registerwerk.

```dotenv
COMPOSE_PROFILES=docs,chaincache-true
CHAINCACHE_ENABLED=true
CHAINCACHE_IMAGE=registerwerk-chaincache:latest
CHAINCACHE_PULL_POLICY=missing
```

```bash
docker compose up -d
docker compose ps chaincache-sepolia chaincache-base
```

Setting `CHAINCACHE_ENABLED=false` leaves those two services out of a fresh Compose deployment.
When switching off an already-running showcase, stop them once before the normal update:

```bash
docker compose stop chaincache-sepolia chaincache-base
docker compose up -d
```

The backend has no `depends_on` edge to them, so a slow or unhealthy optional workload cannot hold
up Registerwerk. Demo seeding follows the same flag: it does not create unreachable showcase nodes
while disabled and disables previously seeded showcase nodes after an opt-out.

Docker Compose has profiles but no native Boolean condition on a service. The `chaincache-true`
profile bridge is how `CHAINCACHE_ENABLED=true` still works with an ordinary `docker compose up -d`
instead of requiring a second CLI option.

## Workload configuration

When enabled, the demo stack runs two chaincache workloads side by side with plain direct-RPC nodes,
so the operator node list shows a real comparison: `chaincache-sepolia` fronts the local
anvil devnet (`DEPTH_BASED`, safe=3/finalized=6), and `chaincache-base` fronts real public Base
Sepolia RPC providers (`TAG_BASED`, `required-provider-agreement: 2`). The entire chaincache-side
configuration for the local devnet workload is:

!!! warning "A chain id is not a chain identity"
    The local Anvil instance reports `11155111` for demo-client compatibility, but it does not
    share public Sepolia's genesis history. The demo therefore seeds only Anvil and its Chaincache
    facade into that Registerwerk node pool. Never add a public Sepolia fallback to the same pool:
    routing, finality comparison and reorg compensation are valid only among replicas of the same
    ledger.

```yaml
# chaincache-sepolia's own compose service
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/chaincache
  CHAINCACHE_CHAINS_SEPOLIA_RPC_NODES_0_PROVIDER: anvil
  CHAINCACHE_CHAINS_SEPOLIA_RPC_NODES_0_HTTP: http://anvil:8545
  CHAINCACHE_CHAINS_SEPOLIA_RPC_NODES_0_WS: ws://anvil:8545
  CHAINCACHE_CHAINS_SEPOLIA_RPC_EXPECTED_CHAIN_ID: "0xaa36a7"
  CHAINCACHE_CHAINS_SEPOLIA_CHAIN_FINALITY_MODEL: DEPTH_BASED
  CHAINCACHE_AUTH_ENABLED: "true"
  CHAINCACHE_JWT_SECRET: ${CHAINCACHE_JWT_SECRET}
```

Two things worth calling out explicitly, both of them real bugs this integration hit and fixed:

!!! note "The chain key must be a single word"
    Spring Boot's relaxed env-var binding for `Map<String, ComplexBean>` properties (which is what
    `chaincache.chains.<key>.*` is) does not reliably bind a multi-word/multi-underscore map key
    (`sepolia-devnet`, `local_devnet`) from environment variables alone — only a single-word key
    (`sepolia`, `anvil`) binds correctly. This is a genuine Spring Boot `Binder` limitation, not a
    chaincache bug; the demo stack's chain keys are deliberately single words for this reason.

!!! note "expected-chain-id is hex, not decimal"
    `RpcProviderIdentityValidator` compares `expected-chain-id` against the upstream's
    `eth_chainId` response, which is always `0x`-prefixed hex. Configuring it as a decimal string
    (`"11155111"` instead of `"0xaa36a7"`) means it can never match, and with
    `identity-validation-required: true` (chaincache's own default) the workload will not start at
    all until this is fixed.

On the Registerwerk side: nothing. Add an RPC node with URL
`http://chaincache-sepolia:8080/sepolia/rpc` through the normal node-add flow (or let
`DemoDataSeeder` seed it, as the demo stack does) — kind, `finalitySource`, and capabilities all
follow automatically within one detection tick of that workload being reachable and authenticated.

## Deployment: shared managed infrastructure, independent releases

chaincache's own [Helm chart](https://github.com/makibytes/chaincache) deploys one release per
chain, each pointed at a shared Cloud SQL instance and a shared Kubernetes Gateway API `HTTPRoute`
— the same shape the demo stack exercises locally with both containers sharing the `chaincache`
database on Registerwerk's own `postgres` service. Registerwerk's own chart does **not** deploy chaincache (it's an independently
versioned, independently deployed product); it carries a `chaincache:` values block naming the
per-chain workload URLs to connect to and a reference to the shared JWT secret, plus a
`NetworkPolicy` egress rule permitting traffic to chaincache's namespace when both charts run in the
same cluster. A worked example: two chaincache Helm releases (`chaincache-sepolia`,
`chaincache-base`) in a `chaincache` namespace, both pointed at one Cloud SQL instance via the same
`Cloud SQL Auth Proxy` sidecar pattern chaincache's own README documents, both scraped by the same
Prometheus instance Registerwerk's monitoring stack already runs; Registerwerk's own chart's
`chaincache.workloads` values list names both `managementUrl`s so `DemoDataSeeder`-equivalent
bootstrapping (or a manual "add node") can wire them up.

### Flyway / PostgreSQL major-version upgrades

chaincache's own migrations are a single clean-install baseline plus incremental `V{n}` files, the
same convention Registerwerk uses. A PostgreSQL major-version bump (the demo stack moved 15→18.6)
is not something Flyway handles by itself — it needs either a `pg_upgrade` in place or a
dump/restore, and the data directory path itself changes between major Postgres image tags
(`/var/lib/postgresql/data` vs. `/var/lib/postgresql` on 18+) — mounting the old volume at the new
image's expected path silently starts a fresh empty cluster instead of failing loudly. Treat a
Postgres major-version bump as its own deliberate migration step, never a side effect of bumping an
image tag. Chaincache's `chaincache` database now lives on the same `postgres` service/volume as
Registerwerk's own `registerwerk` database (not a separate container), so this applies to that one
shared volume for both databases at once — see the migration warnings in
[docker.md](../installation/docker.md#demo-single-host-docker-compose) for the exact steps, both
for the PG-major-version case and for upgrading from an older deployment that still ran a separate
`chaincache-postgres` container.

### Production checklist

- `chaincache.rpc.identity-validation-required: true`, with `expected-chain-id` set as
  `0x`-prefixed hex for every configured chain — an unvalidated or misconfigured chain identity
  means a workload could silently serve the wrong chain's data.
- `chaincache.cache.local-snapshot-enabled: false` and `chaincache.request.local-stats-enabled:
  false` on every replica — the posture this integration's demo stack exercises locally, matching
  what a horizontally-replicated deployment needs (per-replica local state doesn't make sense once
  there's more than one pod behind a workload).
- `chaincache.auth.enabled: true`, with a `registerwerk.chaincache.jwt-secret` that is **not** the
  same value as `JWT_DEV_SECRET` or any other Registerwerk signing key.
- The healthcheck endpoint used for orchestration should be `/actuator/health/readiness`, not
  liveness — a workload can be alive but not yet ready to serve (e.g. still establishing its
  upstream RPC connections) and routing traffic to it during that window produces avoidable probe
  failures on the Registerwerk side.
- `chaincache.auth.prometheus-public: true` only when the scrape happens over a network boundary
  Prometheus already trusts (e.g. the same cluster-internal network, never a published port) — it
  exists specifically so `/actuator/prometheus` doesn't require Registerwerk's own bearer token,
  which Prometheus doesn't carry.

## Observability

Registerwerk's own Prometheus scrapes both chaincache workloads directly (`chaincache_workload`
label distinguishing them) and alerts on workload-down, elevated upstream RPC error rate, WebSocket
flapping, and reorg bursts using chaincache's own Micrometer metrics
(`chaincache.rpc.errors`, `chaincache.rpc.ws.disconnects`, `chaincache.chain.reorganizations`,
`chaincache.rpc.node.latency` — the latter exposes only `_count`/`_sum`, no percentile histogram, so
alerting on it means average latency, not p95). On the Registerwerk side,
`registerwerk_chaincache_stream_connected{chain}` and
`registerwerk_chaincache_stream_last_event_timestamp_seconds{chain}` back two more alerts
(stream disconnected for 5+ minutes; no event received in 10+ minutes despite an open connection —
the second case usually means the chain itself has stopped producing blocks, not that the
integration is broken). A Grafana dashboard (`monitoring/grafana/dashboards/chaincache.json`) covers
per-workload upstream health/latency/reorgs/disconnects plus the Registerwerk-side stream-health
row.

## Deep-linking to the Chaincache console and chaincheck

Every chaincache-kind node's expandable capability panel carries an "Open Chaincache console"
link to `{{ node.managementUrl }}/console` — Chaincache's own operations console (durable-delivery
consumers, retention, reorg history, quarantine, provider health, cache traffic). This needs no
token handoff: `managementUrl` is already part of every `CHAINCACHE`-kind node's response, and
Chaincache serves `/console` as `permitAll()` (the shell is public static code; every management
API it reads still enforces the normal bearer-token check — the operator pastes a short-lived
token into the console itself, kept in memory only).

`managementUrl` is the origin the **backend** uses to reach Chaincache (probing
`GET /api/capabilities`, health checks) — in a deployment where Chaincache has a real, internally
routable hostname this is normally also reachable from an operator's browser (same corporate
network/VPN), so the link works with no further configuration. This repo's own docker-compose demo
stack is the one place that isn't true: `managementUrl` there is the Compose-internal service name
(`chaincache-sepolia:8080`), which the host browser cannot resolve. For that case only, the
operator frontend applies `environment.chaincacheConsoleOriginOverrides` — a small origin→origin
map, empty in `environment.prod.ts`/`environment.testnet.ts` — to swap in the host-published port
(`http://localhost:48090`/`:48091`) before opening the link. This is purely a display-layer
substitution in the frontend; it never touches what the backend itself dials.

[chaincheck](https://github.com/makibytes/chaincheck) is the third sibling product — an
independent node-fleet monitor, not merged into either of the other two. When
`environment.chaincheckUrl` is configured in the operator frontend, the same panel also shows a
"View in chaincheck" link out to it. Both links are purely deep links — Registerwerk does not
query either product's API or depend on it being reachable for anything shown on the node list
itself.
