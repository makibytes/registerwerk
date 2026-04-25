---
id: docker
title: Docker Setup
sidebar_position: 2
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

## Production Docker Compose

The root `docker-compose.yml` defines all services:

```yaml
services:
  postgres:         # Application database
  backend:          # Spring Boot 4 API
  kong-migrations:  # Runs once to migrate Kong DB
  kong:             # API gateway
  konga:            # Kong Admin UI
```

### Starting services

```bash
docker compose up -d
```

On the first start of a fresh `pgdata` volume, Postgres runs the init scripts in `docker/postgres/init/` and creates:

- `registerwerk` owned by `${DB_USER}` with `${DB_PASSWORD}`
- `kong` owned by `${KONG_DB_USER}` with `${KONG_DB_PASSWORD}`
- `konga` owned by `${KONGA_DB_USER}` with `${KONGA_DB_PASSWORD}`

If `pgdata` already exists, those init scripts do not run again.

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
