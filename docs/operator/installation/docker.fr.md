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
`CHAINCACHE_ENABLED=true`. La même commande démarre les deux workloads Chaincache, qui partagent le
même service `postgres` que Registerwerk lui-même (une seconde base de données `chaincache` sur
cette même instance, pas un conteneur Postgres dédié à Chaincache). Registerwerk ne construit
jamais `../chaincache` ; l'image est le seul artefact Chaincache requis. Voir
[Intégration Chaincache](../blockchain/chaincache-integration.md).

Kong fonctionne en mode sans base de données (déclaratif) : `gateway/kong.yml` est monté en lecture seule et est
la source unique de vérité pour les routes et les plugins, il n'y a donc pas de base de données Kong distincte
à migrer ou à amorcer. Au premier démarrage de Postgres, `POSTGRES_DB` crée la base de
données d'application `registerwerk` (propriété de `${DB_USER}`/`${DB_PASSWORD}`), et
`postgres-init/` crée en plus la base de données et le rôle `chaincache` pour les workloads
Chaincache optionnels ci-dessus — que `CHAINCACHE_ENABLED=true` soit défini ou non ; sinon,
elle reste simplement une base de données vide inutilisée.

!!! warning "Migrer un déploiement existant vers le volume pg18data"
    PostgreSQL 18 a déplacé PGDATA sous `/var/lib/postgresql/<major>/docker` et déclare
    `VOLUME /var/lib/postgresql` (et non `.../data`, comme en 17 et avant). Le service `postgres` de
    `docker-compose.yml` monte donc un volume nommé `pg18data`, et non l'ancien `pgdata` — un
    renommage délibéré, pas une faute de frappe : monter un volume antérieur à la version 18 au chemin
    attendu par la nouvelle image démarrerait silencieusement un cluster neuf et vide au lieu d'échouer
    bruyamment. Un déploiement qui migre depuis un ancien volume `pgdata` doit faire un `pg_dump` du
    volume ancien et restaurer dans le nouveau, comme étape de migration explicite, avant d'exécuter
    `docker compose up -d` avec ce fichier compose — ne supposez jamais que le seul renommage emporte les
    données. Le service `graph-db` de `indexer/evm/docker-compose.yml` demande le même traitement
    (`graphdata` → `graph_pg18`).

!!! warning "Migrer depuis un conteneur chaincache-postgres séparé"
    Les révisions antérieures de cette pile exécutaient la base de Chaincache dans son propre conteneur
    `chaincache-postgres` (volume `chaincache_pg18`) au lieu d'une seconde base sur le service `postgres`
    partagé. `postgres-init/01-create-chaincache-db.sql` ne s'exécute que sur un volume `pg18data`
    réellement neuf et vide — exactement comme pour le renommage pg18data ci-dessus —, si bien qu'un
    déploiement existant qui passe à ce fichier compose n'obtiendra **pas** automatiquement la base
    `chaincache`. Avant de supprimer l'ancien conteneur `chaincache-postgres` : faites-en un `pg_dump`
    (`docker compose exec chaincache-postgres pg_dump -U chaincache chaincache | gzip > chaincache.sql.gz`).
    Après la migration, créez la base à la main sur le volume `postgres` existant et restaurez-y la
    sauvegarde :
    ```bash
    docker compose exec postgres psql -U ${DB_USER:-registerwerk} -d registerwerk -c \
      "CREATE USER chaincache WITH PASSWORD 'chaincache'; CREATE DATABASE chaincache OWNER chaincache;"
    gunzip -c chaincache.sql.gz | docker compose exec -T postgres psql -U chaincache chaincache
    ```

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
