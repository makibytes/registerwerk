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
- PostgreSQL auf `localhost:45432`
- Umgebungsvariablen aus `.env` (geladen über [direnv](https://direnv.net/) oder manuell per `export`)

## Flyway-Migrationen

Migrationen laufen beim Start automatisch. Alle Migrationsdateien liegen in:
```
backend/src/main/resources/db/migration/
```

Das Schema besteht aus einer einzigen Clean-Install-Baseline, `V1__initial_schema.sql`, die jede Tabelle, jeden Index, Trigger und jede Funktion abdeckt (einschließlich des Audit-Logs mit Partitionierung und der reinen DML-Berechtigungen des Runtime-Logins `registerwerk_app`). Spätere Änderungen werden als `V{n}__description.sql` hinzugefügt und nach der Veröffentlichung nie mehr bearbeitet.

!!! note
    Datenbanken, die aus Entwicklungs-Builds vor der erneuten Konsolidierung der Baseline erstellt wurden, tragen eine inkompatible Flyway-Historie und können nicht an Ort und Stelle aktualisiert werden; erstellen Sie sie neu.

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
http://localhost:48080/swagger-ui.html
```

Vollständiges OpenAPI-JSON:
```
http://localhost:48080/v3/api-docs
```
