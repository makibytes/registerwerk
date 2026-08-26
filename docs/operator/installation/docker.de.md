---
title: Docker-Setup
---

# Docker-Setup { #docker-setup }

## Erstellen des Backend-Image { #building-the-backend-image }

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

`Dockerfile` verwendet einen zweistufigen Build:
- **Builder**: `eclipse-temurin:25-jdk-alpine` – kompiliert mit Maven
- **Laufzeit**: `eclipse-temurin:25-jre-alpine` – minimales Image, Nicht-Root-Benutzer `ewpg`

## Docker Compose für Demo und Einzelhost

!!! warning "Keine Produktionstopologie"
    Der Root-Stack enthält entsperrte Anvil-Demokonten, SoftHSM, Demo-Deployment-Jobs,
    Entwicklungsschlüssel und lokale Chaincache-Workloads. Er ist die vollständige Showcase- und
    Einzelhost-Testumgebung, nicht die Produktions-/GKE-Topologie.

Die `docker-compose.yml` im Projekt-Root definiert alle Dienste:

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

### Dienste starten { #starting-services }

```bash
docker compose up -d
```

Für den vollständigen Showcase kopieren Sie `.env.example.test` nach `.env`, setzen
`CHAINCACHE_IMAGE` auf ein unabhängig gebautes, geladenes oder abrufbares Image und belassen
`CHAINCACHE_ENABLED=true`. Derselbe normale Befehl startet dann beide Chaincache-Workloads samt
privatem PostgreSQL. Registerwerk baut niemals `../chaincache`; das Image ist das einzige
benötigte Chaincache-Artefakt. Siehe [Chaincache-Integration](../blockchain/chaincache-integration.md).

Kong läuft im DB-losen (deklarativen) Modus: `gateway/kong.yml` wird schreibgeschützt gemountet und ist
die einzige Quelle der Wahrheit für Routen und Plugins, daher gibt es keine separate Kong-Datenbank
zum Migrieren oder Bootstrap – nur die `registerwerk`-Anwendungsdatenbank wird beim ersten Start von Postgres erstellt und gehört `${DB_USER}` mit `${DB_PASSWORD}`.

### Logs { #logs }

```bash
docker compose logs -f backend
docker compose logs -f kong
```

## Ressourcenlimits { #resource-limits }

Zu jedem Dienst in der Produktion hinzufügen:

```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
```

graph-node erfordert mehr Speicher – empfohlen wird ein Limit von mindestens 4 GB.
