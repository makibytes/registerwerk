---
title: API Gateway (Kong)
---

# API Gateway (Kong)

Kong 3.9 (OSS, DB-less) sits in front of the **customer frontend's API traffic only**. It handles
rate limiting, response caching, and security headers. It does **not** front either frontend's UI
— both apps are always opened directly by the browser at their own port (`:44200`, `:44201`) — and
the **operator frontend bypasses Kong entirely**, even for its own API calls (its nginx forwards
`/api/` straight to `backend:8080`). JWT validation and entity/role extraction always happen in
the Spring backend itself, from the token's own claims — not via any
Kong-injected header, in the OSS setup this repo ships.

## Starting Kong

```bash
docker compose up -d kong
```

Kong runs in DB-less (declarative) mode — it reads `gateway/kong.yml` directly via
`KONG_DECLARATIVE_CONFIG` and needs no database of its own.

## Declarative configuration

`gateway/kong.yml` is the single source of truth. It is mounted read-only at `/etc/kong/kong.yml` and loaded at start through `KONG_DECLARATIVE_CONFIG`. Kong is DB-less, so there is no database and no `deck sync` (deck pushes to an Admin API backed by a database, and this stack publishes none). To change routing or plugins, edit the file, validate it, and recreate the container:

```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml   # must print "parse successful"
docker compose up -d --force-recreate kong
```

On Kubernetes, edit `deploy/helm/registerwerk/files/kong.yml` (rendered into the ConfigMap `registerwerk-kong-config`) and run `helm upgrade`; restart the Kong deployment if the pods do not pick up the changed ConfigMap.

## Key plugins

Only bundled Kong OSS plugins are active by default (see `gateway/kong.yml`):

| Plugin | Purpose |
|---|---|
| `proxy-cache` | Caches public-route GET 200 responses for 30-60 seconds |
| `request-transformer` | Strips any client-supplied `X-Entity-Id`/`X-Entity-Roles` on public routes, so nothing can be smuggled in before the backend even sees the request |
| `rate-limiting` | 300 requests/minute, 10,000/hour per client IP (Redis-backed, shared across Kong replicas) |
| `bot-detection` | Blocks common crawler/scanner user agents |
| `ip-restriction` | Restricts `/api/v1/admin/**` to operator-network CIDRs, matched against the real client IP (see below) |
| `cors` | Cross-origin headers for the customer Angular frontend |
| `request-size-limiting` | 20 MB max request body |
| `response-transformer` | Adds standard security headers (HSTS, CSP, X-Frame-Options, …) |

`openid-connect` (JWT termination at the gateway) is **Kong Enterprise/Konnect-only** and not
active in this OSS setup — a ready-to-merge snippet lives at `gateway/plugins/oidc-entra.yml` for
deployments that run Kong Enterprise. Without it, JWT validation and entity/role extraction happen
entirely in the Spring backend, reading the claims off the token itself — Kong never
injects `X-Entity-Id`/`X-Entity-Roles` headers here.

## Client IP handling

Rate limiting, the admin `ip-restriction` and the backend's login throttle all depend on the real
client address, so a client-supplied `X-Forwarded-For` must never be believed:

- The customer nginx **overwrites** `X-Forwarded-For` with the TCP peer it saw (never appends), and
  the operator nginx does the same. Behind the Helm ingress, nginx first restores the real address
  from the ingress controller via `ingress.trustedCidrs`.
- Kong trusts `X-Forwarded-For` only from the nginx/ingress network (`KONG_TRUSTED_IPS`,
  `KONG_REAL_IP_HEADER=X-Forwarded-For`, `KONG_REAL_IP_RECURSIVE=off` in `docker-compose.yml`;
  `kong.env.trusted_ips` in the chart). A caller that reaches the published Kong port directly is
  limited by its own address.
- The backend trusts the header only from `REGISTERWERK_AUTH_TRUSTED_PROXIES` (Compose sets a
  local-demo default; the chart has none and refuses to render until it names only the Kong and operator
  nginx pods, never a whole private range).
- The Compose admin allow list includes `192.168.0.0/16` and `::1` so the local demo works (on
  Docker Desktop the browser arrives from the VM gateway). In Helm the list is rendered from
  `kong.adminAllowCidrs` (required, no default): set your operator networks.

`scripts/check-client-ip.sh` checks against a running stack that spoofed `X-Forwarded-For` values
do not reset the rate-limit counter. The Helm API ingress points at Kong, never at the backend, and
answers 404 for `/actuator/*` other than health.

## Kong admin API

Kong runs DB-less and ships **no admin GUI** in this stack (no Konga, no Kong Manager). The Admin API listens only on `127.0.0.1:8001` **inside the container** (`KONG_ADMIN_LISTEN`) and is not published to the host; it is unauthenticated and must never be exposed. The Kong image has no `curl`, so use the bundled CLI:

```bash
docker compose exec kong kong health
```

To change routing or plugins, edit `gateway/kong.yml` and recreate the `kong` service as described above — it is the single source of truth in DB-less mode.
