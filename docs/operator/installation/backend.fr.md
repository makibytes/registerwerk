---
title: Configuration du backend
---

# Configuration du backend

## Exécution locale

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Le profil `local` lit à partir de `src/main/resources/application-local.yml` et attend :
- PostgreSQL sur `localhost:45432`
- Variables d'environnement de `.env` (chargées via [direnv](https://direnv.net/) ou `export` manuellement)

## Migrations Flyway

Les migrations s'exécutent automatiquement au démarrage. Tous les fichiers de migration sont dans :
```
backend/src/main/resources/db/migration/
```

Le schéma est une unique base de référence d'installation propre, `V1__initial_schema.sql`, couvrant chaque table, index, déclencheur et fonction (y compris le journal d'audit avec son partitionnement et les privilèges DML uniquement du login d'exécution `registerwerk_app`). Les modifications ultérieures sont ajoutées sous la forme `V{n}__description.sql` et ne sont jamais modifiées après publication.

!!! note
    Les bases de données créées à partir de builds de développement antérieurs à la nouvelle consolidation de la base de référence portent un historique Flyway incompatible et ne peuvent pas être mises à niveau sur place ; recréez-les.

## Santé et surveillance

```bash
# Liveness
GET /actuator/health/liveness

# Readiness (checks DB + chain connections)
GET /actuator/health/readiness

# Metrics (Prometheus format)
GET /actuator/prometheus
```

## OpenAPI

L'interface utilisateur de Swagger est disponible sur :
```
http://localhost:48080/swagger-ui.html
```

Spécification OpenAPI complète (JSON) :
```
http://localhost:48080/v3/api-docs
```
