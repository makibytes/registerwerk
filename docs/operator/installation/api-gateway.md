---
title: API Gateway (Kong)
---

# API Gateway (Kong)

Kong 3.8 (OSS, DB-less) sits in front of the **customer frontend's API traffic only**. It handles
rate limiting, response caching, and security headers. It does **not** front either frontend's UI
— both apps are always opened directly by the browser at their own port (`:4200`, `:4201`) — and
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

Kong is configured via `gateway/kong.yml` in deck format. To apply changes:

```bash
deck sync --config gateway/kong.yml
```

## Key plugins

Only bundled Kong OSS plugins are active by default (see `gateway/kong.yml`):

| Plugin | Purpose |
|---|---|
| `proxy-cache` | Caches public-route GET 200 responses for 30-60 seconds |
| `request-transformer` | Strips any client-supplied `X-Entity-Id`/`X-Entity-Roles` on public routes, so nothing can be smuggled in before the backend even sees the request |
| `rate-limiting` | 300 requests/minute, 10,000/hour per consumer |
| `bot-detection` | Blocks common crawler/scanner user agents |
| `ip-restriction` | Restricts `/api/v1/admin/**` to operator-network CIDRs |
| `cors` | Cross-origin headers for the customer Angular frontend |
| `request-size-limiting` | 20 MB max request body |
| `response-transformer` | Adds standard security headers (HSTS, CSP, X-Frame-Options, …) |

`openid-connect` (JWT termination at the gateway) is **Kong Enterprise/Konnect-only** and not
active in this OSS setup — a ready-to-merge snippet lives at `gateway/plugins/oidc-entra.yml` for
deployments that run Kong Enterprise. Without it, JWT validation and entity/role extraction happen
entirely in the Spring backend, reading the claims off the token itself — Kong never
injects `X-Entity-Id`/`X-Entity-Roles` headers here.

## Kong admin API

Kong runs DB-less and ships **no admin GUI** in this stack (no Konga, no Kong Manager — both were
removed/never wired up). Admin API access is intentionally loopback-only:

```bash
# Bound to 127.0.0.1:8001 on the host — never expose this publicly, it's unauthenticated
docker compose exec kong kong health
curl http://127.0.0.1:8001/status
```

To change routing/plugins, edit `gateway/kong.yml` and restart the `kong` service — it's the
single source of truth in DB-less mode.
