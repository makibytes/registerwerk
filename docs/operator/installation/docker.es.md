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

## Docker Compose de demostración / host único { #production-docker-compose }

!!! warning "No es una topología de producción"
    La pila raíz incluye cuentas Anvil desbloqueadas, SoftHSM, trabajos de despliegue de demo,
    secretos de desarrollo y cargas locales de Chaincache. Es el showcase completo para un
    host de pruebas, no la topología de producción/GKE.

La raíz `docker-compose.yml` define todos los servicios:

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

### Iniciando servicios { #starting-services }

```bash
docker compose up -d
```

Para el showcase completo, copie `.env.example.test` a `.env`, configure `CHAINCACHE_IMAGE` con
una imagen creada, cargada u obtenible de forma independiente y mantenga
`CHAINCACHE_ENABLED=true`. El mismo comando inicia ambos workloads Chaincache, que comparten el
mismo servicio `postgres` que usa el propio Registerwerk (una segunda base de datos `chaincache`
en esa misma instancia, no un contenedor Postgres dedicado a Chaincache). Registerwerk nunca
compila `../chaincache`; la imagen es el único artefacto Chaincache necesario. Consulte
[Integración de Chaincache](../blockchain/chaincache-integration.md).

Kong se ejecuta en modo sin base de datos (declarativo): `gateway/kong.yml` está montado como de solo lectura y es
la única fuente de verdad para rutas y complementos, por lo que no existe una base de datos de Kong separada
para migrar o iniciar. Al primer inicio de Postgres, `POSTGRES_DB` crea la base de datos de la
aplicación `registerwerk` (propiedad de `${DB_USER}`/`${DB_PASSWORD}`), y `postgres-init/` crea
además la base de datos y el rol `chaincache` para los workloads Chaincache opcionales anteriores —
siempre, esté o no `CHAINCACHE_ENABLED=true`; si no, simplemente queda como una base de datos vacía
sin usar.

!!! warning "Actualizar un despliegue existente al volumen pg18data"
    PostgreSQL 18 movió PGDATA a `/var/lib/postgresql/<major>/docker` y declara
    `VOLUME /var/lib/postgresql` (no `.../data`, como en la 17 y anteriores). Por eso el servicio
    `postgres` de `docker-compose.yml` monta un volumen llamado `pg18data`, no el antiguo `pgdata` — un
    cambio de nombre deliberado, no un error tipográfico: montar un volumen anterior a la 18 en la ruta
    que espera la nueva imagen arrancaría en silencio un clúster nuevo y vacío en lugar de fallar de forma
    visible. Un despliegue que actualiza desde un volumen `pgdata` antiguo debe hacer un `pg_dump` del
    volumen antiguo y restaurarlo en el nuevo como paso de migración explícito, antes de ejecutar
    `docker compose up -d` con este archivo compose — no dé nunca por hecho que el cambio de nombre
    basta para llevar los datos. El servicio `graph-db` de `indexer/evm/docker-compose.yml` necesita el
    mismo tratamiento (`graphdata` → `graph_pg18`).

!!! warning "Actualizar desde un contenedor chaincache-postgres independiente"
    Las revisiones anteriores de esta pila ejecutaban la base de datos de Chaincache en su propio
    contenedor `chaincache-postgres` (volumen `chaincache_pg18`) en lugar de como segunda base de datos
    del servicio `postgres` compartido. `postgres-init/01-create-chaincache-db.sql` solo se ejecuta contra
    un volumen `pg18data` realmente nuevo y vacío — igual que con el cambio de nombre de pg18data
    anterior —, así que un despliegue existente que pase a este archivo compose **no** obtendrá la base
    de datos `chaincache` automáticamente. Antes de eliminar el antiguo contenedor `chaincache-postgres`:
    haga un `pg_dump` de él (`docker compose exec chaincache-postgres pg_dump -U chaincache chaincache | gzip > chaincache.sql.gz`).
    Tras la actualización, cree la base de datos a mano en el volumen `postgres` existente y restaure en
    ella:
    ```bash
    docker compose exec postgres psql -U ${DB_USER:-registerwerk} -d registerwerk -c \
      "CREATE USER chaincache WITH PASSWORD 'chaincache'; CREATE DATABASE chaincache OWNER chaincache;"
    gunzip -c chaincache.sql.gz | docker compose exec -T postgres psql -U chaincache chaincache
    ```

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
