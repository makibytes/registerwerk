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
| `DB_PASSWORD` | — | **Obligatoire** (mot de passe du compte de migration/propriétaire) |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `DB_USER` / `DB_PASSWORD` | Compte d'exécution avec lequel l'application se connecte (`registerwerk_app` dans les configurations Compose et Helm fournies). Il doit différer du compte propriétaire en mode production, car l'application ne doit pas posséder les tables d'audit |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | — | Compte de migration/propriétaire utilisé uniquement par Flyway (défini sur `DB_USER`/`DB_PASSWORD` dans Compose et Helm) |

## Authentification

### Administrateur intégré (mode sans IdP)

| Variables | Par défaut | Description |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → formulaire nom d'utilisateur/mot de passe dans le frontend opérateur ; `true` → bouton Microsoft |
| `DEFAULT_ADMIN_EMAIL` | — | E-mail de l'utilisateur administrateur prédéfini (mode intégré uniquement) |
| `DEFAULT_ADMIN_PASSWORD` | — | Mot de passe en texte brut haché avec BCrypt à la création de l'administrateur ; utilisé uniquement si aucun `REGISTRY_ADMIN` n'existe, jamais réappliqué à un compte existant |
| `JWT_DEV_SECRET` | intégré | Clé de signature HS256 utilisée en mode développement/démo ; laisser non défini pour le local, remplacer dans l'environnement de staging |

### OAuth2 / OIDC (production)

| Variables | Description |
|---|---|
| `JWT_ISSUER_URI` | URL de l'émetteur OIDC — laisser vide pour le mode développement HS256 ; définir pour la production (par exemple `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | ID client de l'enregistrement d'application de l'API ; utilisé avec le secret pour l'accès Microsoft Graph en mode application seule (statut à deux facteurs, console de support). Non utilisé par Kong, qui ne fait pas d'OIDC |
| `ENTRA_CLIENT_SECRET` | Secret client de l'enregistrement d'application de l'API (identifiant Graph en mode application seule ; obligatoire si `ENTRA_SUPPORT_ENABLED=true`) |

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

## Mode production et portes de mise en production

Définissez `REGISTERWERK_PRODUCTION_MODE=true` sur chaque déploiement de production. Cela transforme les contrôles de disponibilité de simples avertissements en refus de démarrage et active les contrôles propres à la production. [Mode production et portes de mise en production](../security/production-mode.md) (en anglais uniquement) liste chaque porte, la variable qui la commande et ce qui est refusé. Cette page documente aussi les variables de validation et de reconnaissance (`REGISTERWERK_LENDING_RELEASE_APPROVED`, `REGISTERWERK_REPO_DESK_RELEASE_APPROVED`, `REGISTERWERK_TRADING_LEGAL_OPINION_REF`, `REGISTERWERK_AUDIT_ALLOW_OWNER_RUNTIME_ROLE`, `REGISTERWERK_AUDIT_SIGNING_PROVIDER`, `REGISTERWERK_WEBHOOK_ALLOW_INSECURE_URLS`, `REGISTERWERK_WALLET_MASTER_KEY`, `LINK_BY_EMAIL_WITHOUT_VERIFICATION`, `SWAGGER_ENABLED` et d'autres).

## Autres paramètres

Le calendrier du registre, les confirmations de chaîne, la limitation des connexions, le trading, la Travel Rule, le reporting, l'ancrage d'audit et la garde des clés de signature, avec leurs valeurs par défaut et ce que le mode production attend, figurent dans la [référence des paramètres d'exploitation](environment-settings.md) (en anglais uniquement).
