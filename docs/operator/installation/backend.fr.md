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

Versions actuelles du schéma :
| Version | Description |
|---|---|
| V1 | Schéma initial consolidé couvrant les entités juridiques, KYC, les jetons d'intégration, les actifs, les déploiements, les détenteurs, le journal d'audit, la configuration de la chaîne, les transferts de jetons, les curseurs d'état de l'indexeur, ONCHAINID et les attestations, les tables de suite ERC-3643 T-REX, les chaînes Fhenix et Inco et les tables associées |

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
