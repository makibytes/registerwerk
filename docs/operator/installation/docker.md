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

## Production Docker Compose

The root `docker-compose.yml` defines all services:

```yaml
services:
  postgres:           # Application database
  backend:            # Spring Boot 4 API
  kong:               # API gateway — DB-less, routes from gateway/kong.yml
  frontend-operator:  # Operator portal (nginx, direct to backend)
  frontend-customer:  # Customer portal (nginx, proxied through Kong)
```

### Starting services

```bash
docker compose up -d
```

Kong runs in DB-less (declarative) mode: `gateway/kong.yml` is mounted read-only and is
the single source of truth for routes and plugins, so there is no separate Kong database
to migrate or bootstrap — only the `registerwerk` application database is created on
Postgres' first start, owned by `${DB_USER}` with `${DB_PASSWORD}`.

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
