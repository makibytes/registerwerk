---
title: Aperçu de l'API REST
description: Structure des URL, authentification, réponses aux erreurs, pagination et conventions de l'API.
---

# Aperçu de l'API REST { #rest-api-overview }

Toutes les fonctionnalités de Registerwerk sont exposées via une API REST sur `http://backend:8080`. L'interface de l'opérateur se connecte directement ; l'interface client se connecte via Kong (`http://kong:8000`). L'API est documentée avec OpenAPI 3 (interface Swagger UI disponible sur `/swagger-ui.html`).

---

## Structure des URL { #url-structure }

| Modèle | Authentification requise | Disponible pour |
|---|---|---|
| `/api/v1/public/**` | Non | Tout le monde |
| `/api/v1/onboarding/token-info/**` | Non | Flux d'onboarding client |
| `/api/v1/onboarding/complete` | Non | Flux d'onboarding client |
| `/api/v1/**` | JWT requis | Utilisateurs authentifiés (selon le rôle) |

---

## Authentification { #authentication }

Tous les points de terminaison protégés exigent :

```
Authorization: Bearer <jwt>
```

**Le backend valide lui-même chaque jeton, à chaque requête.** Kong ne valide pas les JWT et n'indique pas au backend qui est l'appelant — son plugin `openid-connect` est une fonctionnalité Enterprise et n'est pas actif dans cette configuration OSS. Kong *supprime* en outre les en-têtes d'identité fournis par le client, de sorte que rien ne peut être introduit en contrebande avant d'atteindre le backend.

Les jetons d'opérateur sont émis par `POST /api/v1/public/auth/login` (HS256, `iss: registerwerk-local`). Les jetons client sont émis par le fournisseur OIDC lorsque `ENTRA_ENABLED=true`, et par ce même point de terminaison local dans le cas contraire. Un décodeur délégant achemine la requête selon l'en-tête JWS `alg` ; les deux branches sont épinglées sur l'émetteur (`issuer`) et la branche OIDC est en plus épinglée sur l'audience. Voir [Sécurité et authentification](security.md).

---

## Format des réponses d'erreur { #error-response-format }

Toutes les erreurs suivent l'enregistrement `ErrorResponse` :

```json
{
  "status": 404,
  "message": "Asset with id 'abc...' not found",
  "timestamp": "2026-05-22T10:15:30Z",
  "path": "/api/v1/assets/abc..."
}
```

| Statut HTTP | Levée par | Cause |
|---|---|---|
| 400 | `IllegalArgumentException` | Entrée invalide (échec de validation, valeur d'énumération incorrecte) |
| 401 | `InvalidCredentialsException` | Mot de passe incorrect, JWT expiré |
| 403 | `AccessDeniedException` | Rôle insuffisant, step-up requis |
| 404 | `EntityNotFoundException` | La ressource n'existe pas |
| 409 | `InvalidStateTransitionException` | Opération non autorisée dans l'état actuel (par ex. déployer un actif déjà déployé) |
| 500 | Exception inattendue | Erreur interne du serveur (détails non exposés en production) |

!!! info "Messages d'erreur en production"
    `error.include-message` est réglé sur `never` dans le profil `prod`. En développement et en test, il est réglé sur `always`. Cela évite que des traces de pile ne fuitent dans les réponses en production.

---

## Pagination { #pagination }

Les points de terminaison de liste prennent en charge la pagination par curseur avec les paramètres `page` et `size` :

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

Les réponses incluent un en-tête `X-Total-Count` avec le nombre total d'enregistrements (avant pagination). Le corps de la réponse est toujours un tableau (jamais un objet enveloppant).

---

## Principaux groupes d'API { #key-api-groups }

### Actifs (`/api/v1/assets`) { #assets-apiv1assets }

| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/assets` | Lister tous les actifs (paginé) |
| `POST` | `/api/v1/assets` | Créer un nouvel actif |
| `GET` | `/api/v1/assets/{id}` | Obtenir un actif par ID |
| `POST` | `/api/v1/assets/{id}/deploy` | Déployer le jeton sur la blockchain |
| `POST` | `/api/v1/assets/{id}/mint` | Émettre des jetons (mint) |
| `POST` | `/api/v1/assets/{id}/burn` | Détruire des jetons (burn ; step-up + 4 yeux) |
| `POST` | `/api/v1/assets/{id}/force-transfer` | Transfert forcé (step-up + 4 yeux) |
| `POST` | `/api/v1/assets/{id}/freeze/{address}` | Geler une adresse (nécessite un HolderBlock) |

### Clients (`/api/v1/customers`) { #customers-apiv1customers }

| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/customers` | Lister les entités juridiques |
| `POST` | `/api/v1/customers` | Créer une entité juridique |
| `GET` | `/api/v1/customers/{id}` | Obtenir l'entité |
| `POST` | `/api/v1/customers/{id}/kyc/documents` | Téléverser un document KYC |
| `POST` | `/api/v1/customers/{id}/kyc/approve` | Approuver le KYC (COMPLIANCE_OFFICER + step-up) |
| `GET` | `/api/v1/customers/{id}/beneficial-owners` | Lister les bénéficiaires effectifs (UBO) |
| `POST` | `/api/v1/customers/{id}/beneficial-owners` | Ajouter un bénéficiaire effectif (UBO) |

### Conformité (`/api/v1/compliance`) { #compliance-apiv1compliance }

| Méthode | Chemin | Description |
|---|---|---|
| `POST` | `/api/v1/compliance/screening/entities/{id}/screen` | Déclencher un filtrage manuel |
| `GET` | `/api/v1/compliance/screening/entities/{id}/runs` | Obtenir l'historique de filtrage |
| `POST` | `/api/v1/compliance/screening/hits/{hitId}/accept` | Accepter/rejeter une alerte |
| `GET` | `/api/v1/holder-blocks` | Lister tous les HolderBlocks |
| `POST` | `/api/v1/holder-blocks` | Créer un Sperrvermerk (blocage du titulaire ; step-up + 4 yeux) |
| `POST` | `/api/v1/holder-blocks/{id}/lift` | Lever un Sperrvermerk (step-up + 4 yeux) |

### Reporting réglementaire (`/api/v1/regulatory-reporting`) { #regulatory-reporting-apiv1regulatory-reporting }

| Méthode | Chemin | Description |
|---|---|---|
| `POST` | `/api/v1/regulatory-reporting/mifir` | Déclencher un export MiFIR à la demande |
| `POST` | `/api/v1/regulatory-reporting/dac8` | Déclencher un export DAC8 à la demande |
| `GET` | `/api/v1/regulatory-reporting/submissions` | Lister l'historique des soumissions |

### DORA (`/api/v1/dora`) { #dora-apiv1dora }

| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/dora/incidents` | Lister les incidents ICT ouverts |
| `POST` | `/api/v1/dora/incidents` | Signaler un incident ICT (art. 17) |
| `PATCH` | `/api/v1/dora/incidents/{id}/status` | Mettre à jour le statut de l'incident / la cause première |
| `POST` | `/api/v1/dora/incidents/{id}/report-to-authority` | Enregistrer le rapport initial/final à l'autorité (art. 19) |
| `GET` | `/api/v1/dora/providers` | Lister le registre des prestataires tiers ICT (art. 28) |
| `GET` | `/api/v1/dora/providers/expiring` | Lister les prestataires dont le contrat expire bientôt |
| `GET` | `/api/v1/dora/resilience-tests` | Lister les résultats des tests de résilience (art. 24/25) |
| `GET` | `/api/v1/dora/resilience-tests/overdue` | Lister les tests de résilience en retard |
| `POST` | `/api/v1/dora/resilience-tests` | Enregistrer le résultat d'un test de résilience |

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

La spécification OpenAPI et l'interface interactive sont servies **par le backend** sur le port 8080, et non par ce serveur de documentation.

| URL | Description |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Interface Swagger UI interactive (navigateur) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | JSON OpenAPI 3 (lisible par machine) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Bilan de santé |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Informations de build |

!!! info "Ce site de documentation vs. l'API"
    Ce site (port 8003) est une référence MkDocs statique — il ne fait pas office de proxy vers le backend. Ouvrez les liens ci-dessus directement dans un navigateur pendant que la stack tourne (`docker compose up -d`).

!!! warning "Swagger UI en production"
    Swagger UI est désactivée dans le profil Spring `prod`. Dans les environnements de développement et de préproduction, elle est accessible sans authentification. En production, elle doit être explicitement activée et protégée derrière une liste blanche d'IP ou une authentification de base.
