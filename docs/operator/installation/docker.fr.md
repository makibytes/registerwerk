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

## Production Docker Compose

La racine `docker-compose.yml` définit tous les services :

```yaml
services:
  postgres:           # Application database
  backend:            # Spring Boot 4 API
  kong:               # API gateway — DB-less, routes from gateway/kong.yml
  frontend-operator:  # Operator portal (nginx, direct to backend)
  frontend-customer:  # Customer portal (nginx, proxied through Kong)
```

### Démarrage des services

```bash
docker compose up -d
```

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
