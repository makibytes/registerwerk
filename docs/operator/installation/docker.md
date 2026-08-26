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
`CHAINCACHE_ENABLED=true`. The same ordinary command then starts both Chaincache workloads,
which share the same `postgres` service Registerwerk itself uses (a second `chaincache` database
on that one instance, not a dedicated Chaincache Postgres container). Registerwerk never builds
`../chaincache`; the image is the only Chaincache artifact it requires. See
[Chaincache integration](../blockchain/chaincache-integration.md).

Kong runs in DB-less (declarative) mode: `gateway/kong.yml` is mounted read-only and is
the single source of truth for routes and plugins, so there is no separate Kong database
to migrate or bootstrap. On Postgres' first start, `POSTGRES_DB` creates the `registerwerk`
application database (owned by `${DB_USER}`/`${DB_PASSWORD}`), and `postgres-init/` additionally
creates the `chaincache` database + role used by the optional Chaincache workloads above — always,
whether or not `CHAINCACHE_ENABLED=true`; an unused empty database is the only cost when it isn't.

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

!!! warning "Upgrading from a separate chaincache-postgres container"
    Earlier revisions of this stack ran Chaincache's database in its own dedicated
    `chaincache-postgres` container (volume `chaincache_pg18`) instead of a second database on the
    shared `postgres` service. `postgres-init/01-create-chaincache-db.sql` only runs against a
    genuinely fresh, empty `pg18data` volume — exactly like the pg18data rename above — so an
    existing deployment upgrading onto this compose file will **not** get the `chaincache` database
    automatically. Before removing the old `chaincache-postgres` container: `pg_dump` it
    (`docker compose exec chaincache-postgres pg_dump -U chaincache chaincache | gzip > chaincache.sql.gz`).
    After upgrading, create the database on the existing `postgres` volume by hand and restore into
    it:
    ```bash
    docker compose exec postgres psql -U ${DB_USER:-registerwerk} -d registerwerk -c \
      "CREATE USER chaincache WITH PASSWORD 'chaincache'; CREATE DATABASE chaincache OWNER chaincache;"
    gunzip -c chaincache.sql.gz | docker compose exec -T postgres psql -U chaincache chaincache
    ```

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
