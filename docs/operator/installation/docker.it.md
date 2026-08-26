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

## Docker Compose demo / host singolo { #production-docker-compose }

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
  chaincache-postgres:# Private Chaincache database (optional)
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
`CHAINCACHE_ENABLED=true`. Lo stesso comando avvia entrambi i workload Chaincache e le loro
dipendenza PostgreSQL privata. Registerwerk non compila mai `../chaincache`; l'immagine è
l'unico artefatto Chaincache richiesto. Vedi [Integrazione Chaincache](../blockchain/chaincache-integration.md).

Kong viene eseguito in modalità DB-less (dichiarativa): `gateway/kong.yml` è montato in sola lettura ed è
l'unica fonte di verità per percorsi e plugin, quindi non esiste un database Kong separato
da migrare o avviare: solo il database dell'applicazione `registerwerk` viene creato al primo avvio di
Postgres, di proprietà di `${DB_USER}` con `${DB_PASSWORD}`.

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
