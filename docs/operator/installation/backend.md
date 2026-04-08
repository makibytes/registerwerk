---
id: backend
title: Backend Setup
sidebar_position: 3
---

# Backend Setup

## Running locally

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile reads from `src/main/resources/application-local.yml` and expects:
- PostgreSQL on `localhost:5432`
- Environment variables from `.env` (loaded via [direnv](https://direnv.net/) or `export` manually)

## Flyway migrations

Migrations run automatically on startup. All migration files are in:
```
backend/src/main/resources/db/migration/
```

Current schema versions:
| Version | Description |
|---|---|
| V1 | Legal entities and name history |
| V2 | KYC documents (split metadata / BYTEA) |
| V3 | Onboarding tokens |
| V4 | Assets and deployments |
| V5 | Asset holders |
| V6 | Mint control rules |
| V7 | Audit log (range-partitioned) |
| V8 | JSONB indexes on public_data |
| V9 | Entity merge records |
| V10 | Dynamic chain config |
| V11 | Token transfers |
| V12 | Indexer state cursors |
| V13 | ONCHAINID and claims |
| V14 | ERC-3643 T-REX suite tables |
| V15 | Fhenix and Inco chains |

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
http://localhost:8080/swagger-ui.html
```

Full OpenAPI JSON:
```
http://localhost:8080/v3/api-docs
```
