---
title: Variables d'environnement
---

# Variables d'environnement

Toute la configuration se fait via des variables d'environnement. Copiez `.env.example` dans `.env` et remplissez les valeurs.

## Base de données

| Variables | Par défaut | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | URL de connexion JDBC |
| `DB_USER` | `registerwerk` | Utilisateur de la base de données |
| `DB_PASSWORD` | — | **Obligatoire** |

## Authentification

### Administrateur intégré (mode sans IdP)

| Variables | Par défaut | Description |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → formulaire nom d'utilisateur/mot de passe dans le frontend opérateur ; `true` → bouton Microsoft |
| `DEFAULT_ADMIN_EMAIL` | — | E-mail de l'utilisateur administrateur prédéfini (mode intégré uniquement) |
| `DEFAULT_ADMIN_PASSWORD` | — | Mot de passe en texte brut haché avec BCrypt au démarrage ; faites pivoter en modifiant et en redémarrant |
| `JWT_DEV_SECRET` | intégré | Clé de signature HS256 utilisée en mode développement/démo ; laisser non défini pour le local, remplacer dans l'environnement de staging |

### OAuth2 / OIDC (production)

| Variables | Description |
|---|---|
| `JWT_ISSUER_URI` | URL de l'émetteur OIDC — laisser vide pour le mode développement HS256 ; définir pour la production (par exemple `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | ID client OIDC utilisé par le plugin Kong |
| `ENTRA_CLIENT_SECRET` | Secret client OIDC utilisé par le plugin Kong |
| `ENTRA_ISSUER` | Émetteur OIDC configuré dans le plugin Kong |

## Blockchain RPCs

| Variables | Chaîne |
|---|---|
| `ETH_MAINNET_RPC` | Réseau principal Ethereum |
| `ETH_SEPOLIA_RPC` | Ethereum Sepolia |
| `POLYGON_MAINNET_RPC` | Réseau principal Polygon |
| `POLYGON_AMOY_RPC` | Polygon Amoy |
| `BASE_MAINNET_RPC` | Réseau principal Base |
| `BASE_SEPOLIA_RPC` | Base Sepolia |
| `SOLANA_MAINNET_RPC` | Réseau principal Solana |
| `SOLANA_DEVNET_RPC` | Solana Devnet |
| `REGISTRY_WALLET_PRIVATE_KEY` | Clé de signataire backend pour les opérations blockchain |
| `REGISTRY_SOLANA_PRIVATE_KEY` | Clé de signataire Solana en option |

## Stockage

| Variables | Description |
|---|---|
| `S3_BUCKET` | Nom du compartiment S3 pour les documents KYC |
| `S3_ENDPOINT` | URL du point de terminaison compatible S3 |
| `S3_ACCESS_KEY` | Clé d'accès S3 |
| `S3_SECRET_KEY` | Clé secrète S3 |
| `S3_REGION` | Région S3 |

Les documents de taille inférieure à 5 Mo sont stockés directement en tant que BYTEA dans PostgreSQL. Les documents ≥5 Mo sont stockés dans S3.

## E-mail

| Variables | Description |
|---|---|
| `MAIL_HOST` | Hôte SMTP |
| `MAIL_PORT` | Port SMTP (par défaut 587) |
| `MAIL_USERNAME` | Nom d'utilisateur SMTP |
| `MAIL_PASSWORD` | Mot de passe SMTP |

## Intégration (onboarding)

| Variables | Description |
|---|---|
| `CUSTOMER_FRONTEND_URL` | URL de base du frontend client (pour les liens e-mail) |
| `FRONTEND_BUILD_ENV` | Cible de build frontend : `production` ou `testnet` |
