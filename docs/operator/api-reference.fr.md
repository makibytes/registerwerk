---
title: Référence API
---

# Référence API

Le registre eWpG fournit un REST API pour toutes les opérations de registre. Cette page fournit un aperçu de la structure API, de l'authentification et des liens vers la documentation interactive en direct.

## Documentation interactive

L'interface utilisateur Swagger est disponible à l'adresse :

```
http://localhost:48080/swagger-ui.html
```

Pour la production :

```
https://api.registerwerk.example.com/swagger-ui.html
```

La spécification complète OpenAPI 3 (JSON) est disponible à l'adresse :

```
http://localhost:48080/v3/api-docs
```

## Authentification

Tous les points de terminaison API (à l'exception de `/api/v1/public/**`) nécessitent un jeton Bearer JWT :

```bash
curl https://api.registerwerk.example.com/api/v1/issuances \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIs..."
```

Voir [Authentification](../customer/authentication.md) pour savoir comment obtenir un jeton.

## Groupes API

### Points de terminaison publics (`/api/v1/public/`)

Aucune authentification requise.

| Méthode | Chemin | Description |
|--------|------|-------------|
| `GET` | `/api/v1/public/chains` | Répertorier toutes les chaînes activées |
| `GET` | `/api/v1/public/health` | Vérification de l'état de base |

### Points de terminaison client (`/api/v1/`)

Nécessitent une authentification. Les réponses sont limitées à l'entité authentifiée.

| Méthode | Chemin | Description |
|--------|------|-------------|
| `GET` | `/api/v1/issuances` | Liste des émissions pour votre entité |
| `POST` | `/api/v1/issuances` | Créer une nouvelle émission |
| `GET` | `/api/v1/issuances/{id}` | Obtenir les détails de l'émission |
| `PUT` | `/api/v1/issuances/{id}` | Mettre à jour l'émission (DRAFT uniquement) |
| `POST` | `/api/v1/issuances/{id}/submit` | Soumettre pour approbation |
| `POST` | `/api/v1/issuances/{id}/deploy` | Déployer sur la blockchain |
| `POST` | `/api/v1/issuances/{id}/suspend` | Suspendre le jeton |
| `POST` | `/api/v1/issuances/{id}/redeem` | Marquer comme remboursé |
| `GET` | `/api/v1/issuances/{id}/investors` | Liste des investisseurs |
| `POST` | `/api/v1/issuances/{id}/investors` | Ajouter un investisseur |
| `DELETE` | `/api/v1/issuances/{id}/investors/{investorId}` | Supprimer l'investisseur |
| `POST` | `/api/v1/issuances/{id}/investors/{investorId}/whitelist` | Ajouter le portefeuille à la liste blanche on-chain |
| `GET` | `/api/v1/investments` | Liste des avoirs en jetons (investisseur) |
| `GET` | `/api/v1/transfers` | Liste des transferts pour votre entité |
| `GET` | `/api/v1/audit-log` | Journal d'audit (limité à votre entité) |
| `GET` | `/api/v1/profile` | Votre profil d'entité |
| `POST` | `/api/v1/wallets` | Enregistrer un portefeuille |
| `DELETE` | `/api/v1/wallets/{address}` | Supprimer un portefeuille |

### Points de terminaison d'administrateur (`/api/v1/admin/`)

Nécessitent le rôle `REGISTRY_ADMIN`.

| Méthode | Chemin | Description |
|--------|------|-------------|
| `GET` | `/api/v1/admin/entities` | Lister toutes les entités |
| `POST` | `/api/v1/admin/entities` | Créer une entité + envoyer une invitation |
| `PATCH` | `/api/v1/admin/entities/{id}/status` | Mettre à jour le statut de l'entité |
| `GET` | `/api/v1/admin/kyc` | Liste des vérifications KYC en attente |
| `POST` | `/api/v1/admin/kyc/{id}/approve` | Approuver KYC |
| `POST` | `/api/v1/admin/kyc/{id}/reject` | Rejeter KYC |
| `POST` | `/api/v1/admin/issuances/{id}/approve` | Approuver l'émission |
| `POST` | `/api/v1/admin/issuances/{id}/reject` | Rejeter l'émission |
| `GET` | `/api/v1/admin/chains` | Lister toutes les chaînes |
| `POST` | `/api/v1/admin/chains` | Ajouter une chaîne |
| `PATCH` | `/api/v1/admin/chains/{chainId}` | Mettre à jour la configuration de la chaîne |
| `POST` | `/api/v1/admin/chains/refresh` | Recharger les clients de la chaîne |
| `GET` | `/api/v1/admin/audit-log` | Journal d'audit complet (toutes les entités) |

## Réponses aux erreurs

Toutes les erreurs suivent un format standard :

```json
{
  "timestamp": "2025-04-06T12:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "ISSUANCE_INVALID_STATE",
  "message": "Cannot submit issuance in state ISSUED",
  "path": "/api/v1/issuances/abc123/submit"
}
```

Codes d'erreur courants :

| Code | HTTP | Description |
|------|------|-------------|
| `UNAUTHORIZED` | 401 | JWT manquant ou invalide |
| `FORBIDDEN` | 403 | Rôle insuffisant pour cette opération |
| `NOT_FOUND` | 404 | La ressource n'existe pas |
| `ISSUANCE_INVALID_STATE` | 422 | Transition d'état non autorisée |
| `BLOCKCHAIN_ERROR` | 502 | L'appel RPC à la chaîne a échoué |
| `INDEXER_UNAVAILABLE` | 503 | Graph Node inaccessible |

## Limitation de débit

Les appels API sont limités en débit sur la passerelle Kong :

- 300 requêtes/minute par consommateur authentifié
- 10 requêtes/minute pour les points de terminaison liés à l'authentification

Les en-têtes de limitation de débit sont inclus dans les réponses :

```
X-RateLimit-Limit-Minute: 300
X-RateLimit-Remaining-Minute: 287
```

# Référence API

La spécification OpenAPI complète est disponible à l'adresse :

```
http://localhost:48080/v3/api-docs
http://localhost:48080/swagger-ui.html
```

## Points de terminaison clés

### Entités
| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/entities` | Lister toutes les entités |
| `POST` | `/api/v1/entities` | Créer une entité |
| `GET` | `/api/v1/entities/{id}` | Obtenir l'entité |
| `PUT` | `/api/v1/entities/{id}` | Mettre à jour l'entité |
| `GET` | `/api/v1/entities/{id}/kyc/documents` | Liste des documents KYC |
| `POST` | `/api/v1/entities/{id}/kyc/documents` | Téléverser le document KYC |

### Actifs
| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/assets` | Répertorier tous les actifs |
| `POST` | `/api/v1/assets` | Créer un actif |
| `GET` | `/api/v1/assets/{id}` | Obtenir l'actif |
| `POST` | `/api/v1/assets/{id}/deployments` | Déployer sur la chaîne |
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/history` | Historique des transferts |
| `GET` | `/api/v1/assets/{id}/holders` | Liste des détenteurs |

### ERC-3643
| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/erc3643` | Obtenir la suite T-REX |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/compliance-modules` | Ajouter un module |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/trusted-issuers` | Ajouter un émetteur |

### ONCHAINID
| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/identities` | Liste des identités |
| `POST` | `/api/v1/identities` | Créer ONCHAINID |
| `POST` | `/api/v1/identities/{id}/claims` | Émettre une attestation KYC |
| `DELETE` | `/api/v1/identities/{id}/claims/{claimId}` | Révoquer l'attestation |

### Admin
| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/admin/chains` | Liste des configurations de chaîne |
| `POST` | `/api/v1/admin/chains` | Ajouter une chaîne |
| `PUT` | `/api/v1/admin/chains/{id}` | Mettre à jour la chaîne |
| `POST` | `/api/v1/admin/chains/refresh` | Recharger les clients Web3j |
| `GET` | `/api/v1/audit` | Interroger le journal d'audit |

### Public (pas d'authentification)
| Méthode | Chemin | Description |
|---|---|---|
| `GET` | `/api/v1/public/assets/by-address/{address}` | Rechercher un jeton |
| `GET` | `/api/v1/public/chains` | Liste des chaînes actives |
| `GET` | `/api/v1/onboarding/token-info/{token}` | Valider le jeton d'intégration (onboarding) |
| `POST` | `/api/v1/onboarding/complete` | Terminer l'intégration avec le jeton |
