---
title: Configuration de Docker
---

# Configuration de Docker

## Création de l'image backend

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

Le `Dockerfile` utilise une construction en deux étapes :
- **Builder** : `eclipse-temurin:25-jdk-alpine` — compile avec Maven
- **Runtime** : `eclipse-temurin:25-jre-alpine` — image minimale, utilisateur non root `ewpg`

## Docker Compose de démonstration / hôte unique

!!! warning "Ce n'est pas une topologie de production"
    La pile racine inclut les comptes Anvil déverrouillés, SoftHSM, les tâches de déploiement de
    démonstration, des secrets de développement et des workloads Chaincache locaux. C'est le
    showcase complet pour un hôte de test, pas la topologie de production/GKE.

La racine `docker-compose.yml` définit tous les services :

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

### Démarrage des services

```bash
docker compose up -d
```

Pour le showcase complet, copiez `.env.example.test` vers `.env`, définissez `CHAINCACHE_IMAGE`
sur une image construite, chargée ou récupérable indépendamment et conservez
`CHAINCACHE_ENABLED=true`. La même commande démarre les deux workloads Chaincache ainsi que leurs
dépendance PostgreSQL privée. Registerwerk ne construit jamais `../chaincache` ; l'image
est le seul artefact Chaincache requis. Voir [Intégration Chaincache](../blockchain/chaincache-integration.md).

Kong fonctionne en mode sans base de données (déclaratif) : `gateway/kong.yml` est monté en lecture seule et est
la source unique de vérité pour les routes et les plugins, il n'y a donc pas de base de données Kong distincte
à migrer ou à amorcer — seule la base de données d'application `registerwerk` est créée lors du premier démarrage de
Postgres, propriété de `${DB_USER}` avec `${DB_PASSWORD}`.

### Journaux

```bash
docker compose logs -f backend
docker compose logs -f kong
```

## Limites des ressources

Ajouter à chaque service en production :

```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
```
graph-node nécessite plus de mémoire — nous recommandons une limite d'au moins 4 Go.
