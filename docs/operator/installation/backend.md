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

The schema is one clean-install baseline, `V1__initial_schema.sql`, covering every table, index, trigger and function (including the audit log with its partitioning and the DML-only privileges of the `registerwerk_app` runtime login). Later changes are added as `V{n}__description.sql` and are never edited after release.

!!! note
    Databases created from development builds before the baseline was re-squashed carry an incompatible Flyway history and cannot be upgraded in place; recreate them.

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
