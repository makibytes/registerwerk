---
title: Référence API
---

# Référence API

Registerwerk expose une API REST pour toutes les opérations du registre. Cette page est le point d'entrée de l'opérateur ; les conventions (erreurs, pagination, idempotence, montants) figurent dans la [vue d'ensemble de l'API REST](../platform/api.md), et chaque route est listée dans l'[index des routes API](../platform/api-routes.md) généré (en anglais uniquement).

## Documentation interactive

Le document OpenAPI et Swagger UI sont **désactivés par défaut**. Définissez `SWAGGER_ENABLED=true` pour que le backend les serve :

```
http://localhost:48080/swagger-ui.html
http://localhost:48080/api-docs
```

!!! warning "La spécification est non authentifiée lorsqu'elle est activée"
    Avec `SWAGGER_ENABLED=true`, ces chemins sont `permitAll`. Rien ne refuse ce réglage en mode production. Laissez-le désactivé sur les déploiements exposés à Internet.

## Authentification

Tous les points d'entrée de l'API, sauf `/api/v1/public/**` (et les points d'entrée de jeton d'onboarding), exigent un JWT Bearer :

```bash
curl http://localhost:48080/api/v1/entities \
  -H "Authorization: Bearer <jwt>"
```

Les jetons opérateur proviennent de `POST /api/v1/public/auth/login` (le portail opérateur l'utilise directement). Avec `ENTRA_ENABLED=true`, les jetons client proviennent d'Entra ; sinon du même point d'entrée local. Voir [Sécurité et authentification](../platform/security.md) et, côté client, [Connexion](../customer/authentication.md). Le backend valide lui-même chaque jeton ; Kong ne le fait pas.

## Où trouver un point d'entrée

| Besoin | Où |
|---|---|
| Chaque route, son expression de rôle et son exigence de step-up | [Index des routes API](../platform/api-routes.md) |
| Quelles routes exigent une authentification renforcée ou un deuxième approbateur, et quelles opérations lient le corps de la requête | [Matrice step-up](../compliance/step-up-matrix.md) |
| Schémas de requête et de réponse | Document OpenAPI (`SWAGGER_ENABLED=true`) |
| Format d'erreur, pagination, `Idempotency-Key`, montants décimaux | [Vue d'ensemble de l'API REST](../platform/api.md) |

## Réponses d'erreur

Les erreurs utilisent le record `ErrorResponse` (`status`, `message`, `timestamp`, `path`) ; la correspondance des statuts (400, 401, 403, 404, 409, 500) est documentée dans la [vue d'ensemble de l'API REST](../platform/api.md#error-response-format). Certains points d'entrée ajoutent un `code` lisible par machine, par exemple `IDEMPOTENCY_KEY_REQUIRED`, `IMPERSONATION_READ_ONLY` ou `IMPERSONATION_ACTION_DENIED`.

## Limitation de débit

Les appels à l'API client passent par Kong, qui limite chaque IP cliente à 300 requêtes par minute et 10 000 par heure, comptées dans Redis afin que la limite soit partagée entre les réplicas de Kong (voir [Passerelle API](installation/api-gateway.md)). Les appels du portail opérateur ne passent pas par Kong et ne sont pas soumis à cette limite. Des en-têtes de limitation (`X-RateLimit-Limit-Minute`, `X-RateLimit-Remaining-Minute`) figurent dans les réponses ; une limite dépassée est signalée par `429`.
