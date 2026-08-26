---
title: Docker Setup
---

# Docker Setup

## Building the backend image

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

The `Dockerfile` uses a two-stage build:
- **Builder**: `eclipse-temurin:25-jdk-alpine` — compiles with Maven
- **Runtime**: `eclipse-temurin:25-jre-alpine` — minimal image, non-root user `ewpg`

## Demo / single-host Docker Compose

!!! warning "Not a production topology"
    The root stack includes disposable Anvil accounts, SoftHSM, demo deployment jobs, development
    secrets and local Chaincache workloads. It is the complete showcase and a convenient
    single-host test environment, not the production/GKE topology.

The root `docker-compose.yml` defines all services:

```yaml
services:
  postgres:           # Application database
  anvil:              # Disposable local EVM (host 48545, container-only 8545)
  softhsm:            # Disposable demo signing token
  demo-onchain-deploy:# One-shot contract deployment
  backend:            # Spring Boot 4 API
  kong:               # API gateway — DB-less, routes from gateway/kong.yml
  frontend-operator:  # Operator portal (nginx, direct to backend)
  frontend-customer:  # Customer portal (nginx, proxied through Kong)
  chaincache-postgres:# Private Chaincache database (optional)
  chaincache-sepolia: # Local-Anvil Chaincache workload (optional)
  chaincache-base:    # Base Sepolia Chaincache workload (optional)
  docs:               # Documentation server (docs profile)
```

### Starting services

```bash
docker compose up -d
```

For the full showcase, copy `.env.example.test` to `.env`, set
`CHAINCACHE_IMAGE` to an image you have independently built, loaded or can pull, and keep
`CHAINCACHE_ENABLED=true`. The same ordinary command then starts both Chaincache workloads and
their private PostgreSQL dependency. Registerwerk never builds `../chaincache`; the image
is the only Chaincache artifact it requires. See [Chaincache integration](../blockchain/chaincache-integration.md).

Kong runs in DB-less (declarative) mode: `gateway/kong.yml` is mounted read-only and is
the single source of truth for routes and plugins, so there is no separate Kong database
to migrate or bootstrap — only the `registerwerk` application database is created on
Postgres' first start, owned by `${DB_USER}` with `${DB_PASSWORD}`.

!!! warning "Upgrading an existing deployment onto the pg18data volume"
    PostgreSQL 18 moved PGDATA under `/var/lib/postgresql/<major>/docker` and declares
    `VOLUME /var/lib/postgresql` (not `.../data`, as on 17 and earlier). `docker-compose.yml`'s
    `postgres` service therefore mounts a volume named `pg18data`, not the old `pgdata` — a
    deliberate rename, not a typo: mounting a pre-18 volume at the new image's expected path
    would silently start a fresh, empty cluster instead of failing loudly. A deployment upgrading
    from an older `pgdata` volume must `pg_dump` from the old volume and restore into the new one
    as its own explicit migration step before running `docker compose up -d` against this compose
    file — never assume the rename alone carries the data across. `indexer/evm/docker-compose.yml`'s
    `graph-db` service needs the identical treatment (`graphdata` → `graph_pg18`).

### Logs

```bash
docker compose logs -f backend
docker compose logs -f kong
```

## Resource limits

Add to each service in production:

```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
```

graph-node requires more memory — recommend at least 4 GB limit.
