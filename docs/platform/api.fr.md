---
title: Aperçu de l'API REST
description: Structure des URL, authentification, réponses aux erreurs, pagination et conventions de l'API.
---

# Aperçu de l'API REST { #rest-api-overview }

Toutes les fonctionnalités de Registerwerk sont exposées via une API REST sur `http://backend:8080`. L'interface de l'opérateur se connecte directement ; l'interface client se connecte via Kong (`http://kong:8000`). Chaque route mappée figure dans l'[index des routes API](api-routes.md) généré (en anglais uniquement). Un document OpenAPI 3 et une interface Swagger UI existent mais sont **désactivés par défaut** (voir [OpenAPI / Swagger UI](#openapi-swagger-ui)).

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

Les endpoints de liste qui paginent acceptent `page` (à partir de zéro) et `size`, par exemple :

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

La forme de la réponse dépend **de l'endpoint** : certains renvoient un simple tableau JSON (le total figure alors dans l'en-tête de réponse `X-Total-Count`, que la configuration CORS expose aux navigateurs), d'autres l'enveloppe `PageResponse` `{ content, totalElements, totalPages, page, size }`. Vérifiez le schéma de l'endpoint dans le document OpenAPI avant de vous fier à l'une ou l'autre forme.

---

## Idempotency-Key et montants { #idempotency-key-and-amounts }

Les endpoints d'administration/d'émetteur qui déplacent des fonds ou modifient un état exigent l'en-tête `Idempotency-Key` : mint, burn, transferts/approbations forcés, force-burn, gel et modifications de liste blanche, administration des jetons Solana, opérations de slot et de vault, actions d'agent ERC-3643, opérations et rapprochement des marchés de prêt, modifications de moyens de paiement, import de wallet, remise/finalisation de transferts de registre et remboursement d'un actif. Un `POST`, `PUT`, `PATCH` ou `DELETE` sans clé valide est rejeté avec `400` et le code `IDEMPOTENCY_KEY_REQUIRED` (ou `IDEMPOTENCY_KEY_INVALID`) avant toute exécution. Les autres endpoints restent facultatifs.

- Envoyez une valeur unique par action utilisateur (UUID ; 8 à 255 caractères parmi `A-Za-z0-9._:-`) et **réutilisez la même valeur lorsque vous répétez la même requête** après un délai dépassé ou une erreur `5xx`. La répétition renvoie alors le résultat initial (`X-Idempotent-Replay: true`) ou la même transaction au lieu de s'exécuter deux fois.
- La clé est propre à l'appelant : l'entité juridique pour les jetons clients, l'utilisateur agissant pour les jetons opérateur. La même clé avec une autre méthode, un autre chemin ou un autre corps reçoit `422` ; une requête encore en cours reçoit `409`.
- La clé est aussi enregistrée sur la ligne d'outbox de la transaction on-chain ; une répétition correspond donc à la même transaction signée, même après expiration de la réponse en cache. Les réponses `401`/`403` (y compris les défis de step-up) et `5xx` ne sont pas mises en cache : répéter la requête après un step-up avec la même clé est sans risque.

**Les montants sont des chaînes décimales.** Envoyez les montants de jetons (`amount`, `value`, `newCap`, `navPerShare`, ...) sous forme de chaînes JSON telles que `"1000000000000000000000"`. Un nombre JavaScript perd en précision au-delà de 2^53. Pendant une version, un nombre JSON reste accepté s'il est exactement représentable (entier inférieur à 2^53 ou décimal d'au plus 15 chiffres significatifs) et un avertissement de dépréciation est journalisé ; tout le reste reçoit `400` avec `Invalid amount: ...`.

## Groupes de routes { #route-groups }

L'[index des routes API](api-routes.md) généré est la liste complète, dérivée du code (méthode, chemin, expression de rôle, step-up). Principaux chemins de base :

| Domaine | Chemin de base |
|---|---|
| Actifs et déploiements (mint/burn émetteur sous `.../deployments/{depId}/issuer/`, opérations forcées de l'opérateur sous `.../deployments/{depId}/admin/`) | `/api/v1/assets`, `/api/v1/deployments` |
| Entités juridiques et KYC (`/api/v1/entities/{entityId}/kyc/...`), file de revue KYC | `/api/v1/entities`, `/api/v1/kyc` |
| Filtrage des sanctions (`/api/v1/compliance/screening/...`, correspondances sous `/hits/{hitId}/accept`) et autres fonctions de conformité | `/api/v1/compliance` |
| Sperrvermerk (blocages de titulaire) | `/api/v1/holder-blocks` |
| Déclarations réglementaires (MiFIR, DAC8) | `/api/v1/regulatory-reporting` |
| Incidents, prestataires et tests de résilience DORA | `/api/v1/dora` |
| Négociation, repo desk, lending, opérations sur titres | `/api/v1/trading`, `/api/v1/repo-desk`, `/api/v1/lending`, `/api/v1/corporate-actions` |
| File d'approbation des quatre yeux | `/api/v1/approvals` |
| Journal d'audit, vérification de la chaîne | `/api/v1/audit` |
| Administration opérateur (utilisateurs, wallets, ...) | `/api/v1/admin` |
| Libre-service des entreprises clientes | `/api/v1/company`, `/api/v1/me` |
| Public, sans authentification (chaînes, capacités de la plateforme, Travel Rule) | `/api/v1/public` |

Approuver un KYC, par exemple, c'est `POST /api/v1/entities/{entityId}/kyc/approve` : l'initiateur est un `REGISTRY_ADMIN` ou un `COMPLIANCE_OFFICER`, et l'appel exige une authentification renforcée et un deuxième approbateur (voir la [matrice step-up](../compliance/step-up-matrix.md)).

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

Le document OpenAPI et l'interface Swagger UI sont servis **par le backend**, et non par ce serveur de documentation, et sont **désactivés sauf si `SWAGGER_ENABLED=true`** (par défaut `false`, dans tous les profils).

| URL (si activé) | Description |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Swagger UI interactive (navigateur) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | JSON OpenAPI 3 (lisible par machine) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Contrôle de santé |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Informations de build |

!!! info "Ce site de documentation et l'API"
    Ce site (port 48003) est une référence MkDocs statique — il ne fait pas de proxy vers le backend. Ouvrez les liens ci-dessus directement dans un navigateur tant que la pile fonctionne (`docker compose up -d`).

!!! warning "Activer SWAGGER_ENABLED expose la spécification sans authentification"
    Lorsqu'il est activé, `/swagger-ui.html`, `/swagger-ui/**` et `/api-docs/**` sont publics (`permitAll` dans la configuration de sécurité) : quiconque peut atteindre le backend peut lire tout le catalogue de routes et de schémas. Rien ne refuse `SWAGGER_ENABLED=true` en mode production. Laissez-le désactivé en production, ou placez le backend derrière une liste d'autorisation qui exclut ces chemins.
