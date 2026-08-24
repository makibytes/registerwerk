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

## Production Docker Compose { #production-docker-compose }

Il `docker-compose.yml` alla radice del repository definisce tutti i servizi:

```yaml
services:
  postgres:           # Application database
  backend:            # Spring Boot 4 API
  kong:               # API gateway — DB-less, routes from gateway/kong.yml
  frontend-operator:  # Operator portal (nginx, direct to backend)
  frontend-customer:  # Customer portal (nginx, proxied through Kong)
```

### Avvio dei servizi { #starting-services }

```bash
docker compose up -d
```

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
