---
title: Backend Setup
---

# Backend Setup

## Running locally

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile reads from `src/main/resources/application-local.yml` and expects:
- PostgreSQL on `localhost:45432`
- Environment variables from `.env` (loaded via [direnv](https://direnv.net/) or `export` manually)

## Flyway migrations

Migrations run automatically on startup. All migration files are in:
```
backend/src/main/resources/db/migration/
```

Current schema versions:
| Version | Description |
|---|---|
| V1 | Consolidated initial schema covering legal entities, KYC, onboarding tokens, assets, deployments, holders, audit log, chain config, token transfers, indexer state cursors, ONCHAINID and claims, ERC-3643 T-REX suite tables, Fhenix and Inco chains, and related tables |

## Health and monitoring

```bash
# Liveness
GET /actuator/health/liveness

# Readiness (checks DB + chain connections)
GET /actuator/health/readiness

# Metrics (Prometheus format)
GET /actuator/prometheus
```

## OpenAPI

Swagger UI is available at:
```
http://localhost:48080/swagger-ui.html
```

Full OpenAPI JSON:
```
http://localhost:48080/v3/api-docs
```

## Kubernetes / GKE

The Helm chart supports either its PostgreSQL subchart or a Cloud SQL Auth Proxy native sidecar,
never both. The latter requires Kubernetes 1.29+, Workload Identity Federation, and a service
account with `roles/cloudsql.client`. It can also render Google Managed Prometheus `PodMonitoring`.
See `deploy/helm/registerwerk/README.md` in the source repository for exact values, security
context, rollout, probe, and validation behavior.
