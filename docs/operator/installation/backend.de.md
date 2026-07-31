---
title: Backend-Setup
---

# Backend-Setup

## Lokal ausführen

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Das `local`-Profil liest aus `src/main/resources/application-local.yml` und erwartet:
- PostgreSQL auf `localhost:5432`
- Umgebungsvariablen aus `.env` (geladen über [direnv](https://direnv.net/) oder manuell per `export`)

## Flyway-Migrationen

Migrationen laufen beim Start automatisch. Alle Migrationsdateien liegen in:
```
backend/src/main/resources/db/migration/
```

Aktuelle Schemaversionen:
| Version | Beschreibung |
|---|---|
| V1 | Konsolidiertes ursprüngliches Schema für juristische Personen, KYC, Onboarding-Token, Assets, Bereitstellungen, Inhaber, Audit-Log, Chain-Konfiguration, Token-Übertragungen, Indexer-State-Cursor, ONCHAINID und Claims, ERC-3643-T-REX-Suite-Tabellen, Fhenix- und Inco-Chains sowie zugehörige Tabellen |

## Gesundheit und Überwachung

```bash
# Liveness
GET /actuator/health/liveness

# Readiness (checks DB + chain connections)
GET /actuator/health/readiness

# Metrics (Prometheus format)
GET /actuator/prometheus
```

## OpenAPI

Swagger UI ist verfügbar unter:
```
http://localhost:8080/swagger-ui.html
```

Vollständiges OpenAPI-JSON:
```
http://localhost:8080/v3/api-docs
```
