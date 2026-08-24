---
title: Configuración de Docker
---

# Configuración de Docker { #docker-setup }

## Construyendo la imagen de backend { #building-the-backend-image }

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

El `Dockerfile` utiliza una compilación de dos etapas:
- **Builder**: `eclipse-temurin:25-jdk-alpine` — compila con Maven
- **Runtime**: `eclipse-temurin:25-jre-alpine` — imagen mínima, usuario no root `ewpg`

## Docker Compose de producción { #production-docker-compose }

La raíz `docker-compose.yml` define todos los servicios:

```yaml
services:
  postgres:           # Application database
  backend:            # Spring Boot 4 API
  kong:               # API gateway — DB-less, routes from gateway/kong.yml
  frontend-operator:  # Operator portal (nginx, direct to backend)
  frontend-customer:  # Customer portal (nginx, proxied through Kong)
```

### Iniciando servicios { #starting-services }

```bash
docker compose up -d
```

Kong se ejecuta en modo sin base de datos (declarativo): `gateway/kong.yml` está montado como de solo lectura y es
la única fuente de verdad para rutas y complementos, por lo que no existe una base de datos de Kong separada
para migrar o iniciar; solo se crea la base de datos de la aplicación `registerwerk` en el primer inicio de
Postgres, propiedad de `${DB_USER}` con `${DB_PASSWORD}`.

### Registros { #logs }

```bash
docker compose logs -f backend
docker compose logs -f kong
```

## Límites de recursos { #resource-limits }

Agregar a cada servicio en producción:

```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
```
graph-node requiere más memoria; se recomienda un límite de al menos 4 GB.
