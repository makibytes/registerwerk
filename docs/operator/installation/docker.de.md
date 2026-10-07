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

## Docker Compose für Demo und Einzelhost { #demo-single-host-docker-compose }

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
`CHAINCACHE_ENABLED=true`. Derselbe normale Befehl startet dann beide Chaincache-Workloads, die sich
denselben `postgres`-Dienst wie Registerwerk selbst teilen (eine zweite Datenbank `chaincache` auf
dieser einen Instanz, kein eigener Chaincache-Postgres-Container). Registerwerk baut niemals
`../chaincache`; das Image ist das einzige benötigte Chaincache-Artefakt. Siehe
[Chaincache-Integration](../blockchain/chaincache-integration.md).

Kong läuft im DB-losen (deklarativen) Modus: `gateway/kong.yml` wird schreibgeschützt gemountet und ist
die einzige Quelle der Wahrheit für Routen und Plugins, daher gibt es keine separate Kong-Datenbank
zum Migrieren oder Bootstrap. Beim ersten Start von Postgres erstellt `POSTGRES_DB` die
`registerwerk`-Anwendungsdatenbank (gehört `${DB_USER}`/`${DB_PASSWORD}`), und `postgres-init/`
erstellt zusätzlich die Datenbank + Rolle `chaincache` für die optionalen Chaincache-Workloads oben –
unabhängig davon, ob `CHAINCACHE_ENABLED=true` gesetzt ist; andernfalls bleibt sie einfach ungenutzt.

!!! warning "Bestehende Installation auf das Volume pg18data umstellen"
    PostgreSQL 18 hat PGDATA nach `/var/lib/postgresql/<major>/docker` verschoben und deklariert
    `VOLUME /var/lib/postgresql` (nicht `.../data` wie bis Version 17). Der Dienst `postgres` in
    `docker-compose.yml` bindet deshalb ein Volume namens `pg18data` ein, nicht das alte `pgdata` — eine
    bewusste Umbenennung, kein Tippfehler: Ein Volume aus der Zeit vor Version 18 am neuen Pfad des Images
    einzuhängen würde stillschweigend einen frischen, leeren Cluster starten, statt laut zu scheitern.
    Eine Installation, die von einem älteren `pgdata`-Volume aus umgestellt wird, muss als eigenen,
    ausdrücklichen Migrationsschritt per `pg_dump` aus dem alten Volume sichern und in das neue
    zurückspielen, bevor `docker compose up -d` gegen diese Compose-Datei läuft — nehmen Sie nie an, dass
    die Umbenennung allein die Daten mitnimmt. Der Dienst `graph-db` in `indexer/evm/docker-compose.yml`
    braucht dieselbe Behandlung (`graphdata` → `graph_pg18`).

!!! warning "Umstellung von einem separaten chaincache-postgres-Container"
    Frühere Stände dieses Stacks betrieben die Chaincache-Datenbank in einem eigenen Container
    `chaincache-postgres` (Volume `chaincache_pg18`) statt als zweite Datenbank im gemeinsamen Dienst
    `postgres`. `postgres-init/01-create-chaincache-db.sql` läuft nur gegen ein wirklich frisches, leeres
    `pg18data`-Volume — genau wie bei der pg18data-Umbenennung oben —, daher erhält eine bestehende
    Installation, die auf diese Compose-Datei umstellt, die Datenbank `chaincache` **nicht** automatisch.
    Bevor Sie den alten Container `chaincache-postgres` entfernen: Sichern Sie ihn per `pg_dump`
    (`docker compose exec chaincache-postgres pg_dump -U chaincache chaincache | gzip > chaincache.sql.gz`).
    Legen Sie nach der Umstellung die Datenbank auf dem bestehenden `postgres`-Volume von Hand an und
    spielen Sie die Sicherung ein:
    ```bash
    docker compose exec postgres psql -U ${DB_USER:-registerwerk} -d registerwerk -c \
      "CREATE USER chaincache WITH PASSWORD 'chaincache'; CREATE DATABASE chaincache OWNER chaincache;"
    gunzip -c chaincache.sql.gz | docker compose exec -T postgres psql -U chaincache chaincache
    ```

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
