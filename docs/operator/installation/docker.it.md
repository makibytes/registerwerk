---
title: Configurazione Docker
---

# Configurazione Docker { #docker-setup }

## Creazione dell'immagine backend { #building-the-backend-image }

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

`Dockerfile` utilizza una build in due fasi:
- **Builder**: `eclipse-temurin:25-jdk-alpine` — compila con Maven
- **Runtime**: `eclipse-temurin:25-jre-alpine` — immagine minima, utente non root `ewpg`

## Docker Compose demo / host singolo { #demo-single-host-docker-compose }

!!! warning "Non è una topologia di produzione"
    Lo stack radice include account Anvil sbloccati, SoftHSM, job di deployment demo, segreti di
    sviluppo e workload Chaincache locali. È lo showcase completo per un host di test, non la
    topologia di produzione/GKE.

Il `docker-compose.yml` alla radice del repository definisce tutti i servizi:

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

### Avvio dei servizi { #starting-services }

```bash
docker compose up -d
```

Per lo showcase completo, copia `.env.example.test` in `.env`, imposta `CHAINCACHE_IMAGE` su
un'immagine creata, caricata o scaricabile in modo indipendente e mantieni
`CHAINCACHE_ENABLED=true`. Lo stesso comando avvia entrambi i workload Chaincache, che condividono
lo stesso servizio `postgres` usato da Registerwerk stesso (un secondo database `chaincache`
su quella stessa istanza, non un container Postgres dedicato a Chaincache). Registerwerk non
compila mai `../chaincache`; l'immagine è l'unico artefatto Chaincache richiesto. Vedi
[Integrazione Chaincache](../blockchain/chaincache-integration.md).

Kong viene eseguito in modalità DB-less (dichiarativa): `gateway/kong.yml` è montato in sola lettura ed è
l'unica fonte di verità per percorsi e plugin, quindi non esiste un database Kong separato
da migrare o avviare. Al primo avvio di Postgres, `POSTGRES_DB` crea il database
dell'applicazione `registerwerk` (di proprietà di `${DB_USER}`/`${DB_PASSWORD}`), e
`postgres-init/` crea inoltre il database e il ruolo `chaincache` usati dai workload
Chaincache opzionali sopra — indipendentemente dal fatto che `CHAINCACHE_ENABLED=true` sia
impostato o meno; in caso contrario resta semplicemente un database vuoto inutilizzato.

!!! warning "Aggiornare un deployment esistente al volume pg18data"
    PostgreSQL 18 ha spostato PGDATA sotto `/var/lib/postgresql/<major>/docker` e dichiara
    `VOLUME /var/lib/postgresql` (non `.../data`, come nella 17 e precedenti). Il servizio `postgres` di
    `docker-compose.yml` monta quindi un volume chiamato `pg18data`, non il vecchio `pgdata` — una
    ridenominazione voluta, non un refuso: montare un volume precedente alla 18 nel percorso atteso dalla
    nuova immagine avvierebbe silenziosamente un cluster nuovo e vuoto invece di fallire in modo
    evidente. Un deployment che aggiorna da un vecchio volume `pgdata` deve eseguire un `pg_dump` dal
    volume vecchio e ripristinare in quello nuovo come passo di migrazione esplicito, prima di eseguire
    `docker compose up -d` con questo file compose — non dare mai per scontato che la sola
    ridenominazione porti con sé i dati. Il servizio `graph-db` di `indexer/evm/docker-compose.yml`
    richiede lo stesso trattamento (`graphdata` → `graph_pg18`).

!!! warning "Aggiornare da un container chaincache-postgres separato"
    Le revisioni precedenti di questo stack eseguivano il database di Chaincache in un proprio container
    `chaincache-postgres` (volume `chaincache_pg18`) invece che come secondo database sul servizio
    `postgres` condiviso. `postgres-init/01-create-chaincache-db.sql` viene eseguito solo su un volume
    `pg18data` davvero nuovo e vuoto — esattamente come per la ridenominazione di pg18data sopra —, quindi
    un deployment esistente che passa a questo file compose **non** otterrà automaticamente il database
    `chaincache`. Prima di rimuovere il vecchio container `chaincache-postgres`: eseguine un `pg_dump`
    (`docker compose exec chaincache-postgres pg_dump -U chaincache chaincache | gzip > chaincache.sql.gz`).
    Dopo l'aggiornamento, crea il database a mano sul volume `postgres` esistente e ripristina in esso:
    ```bash
    docker compose exec postgres psql -U ${DB_USER:-registerwerk} -d registerwerk -c \
      "CREATE USER chaincache WITH PASSWORD 'chaincache'; CREATE DATABASE chaincache OWNER chaincache;"
    gunzip -c chaincache.sql.gz | docker compose exec -T postgres psql -U chaincache chaincache
    ```

### Registri { #logs }

```bash
docker compose logs -f backend
docker compose logs -f kong
```

## Limiti risorse { #resource-limits }

Aggiungi a ciascun servizio in produzione:

```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
```

graph-node richiede più memoria: si consiglia un limite minimo di 4 GB.
