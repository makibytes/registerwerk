---
title: Mise à niveau du registre
---

# Mise à niveau du registre

Cette page couvre la mise à niveau du backend, des frontends et des contrats intelligents. Suivez les procédures dans l'ordre : ne mettez jamais à niveau les contrats avant de mettre à niveau le backend.

## Mise à niveau du backend

### 1. Extrayez les dernières modifications

```bash
git fetch origin
git pull origin main
git submodule update --recursive
```

### 2. Consultez le journal des modifications

Examinez les commits et les changements de configuration entre la version actuellement déployée et la version cible avant de continuer.

### 3. Créez la nouvelle image backend

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

Ou extrayez-la du registre de conteneurs :

```bash
docker pull ghcr.io/ewpg/registerwerk-backend:latest
```

### 4. Appliquer la mise à niveau

```bash
# Stop the backend gracefully (drains in-flight requests)
docker compose stop backend

# Start the new version — Flyway runs migrations automatically on startup
docker compose up -d backend

# Verify health
docker compose logs -f backend | grep -E "Started|ERROR"
curl http://localhost:48080/actuator/health
```

!!! warning
    Les migrations de bases de données s'exécutent automatiquement au démarrage. Si une migration échoue, le backend ne démarrera pas. Vérifiez les journaux pour l'erreur de migration spécifique. Ne modifiez jamais manuellement la table d'historique Flyway.


### 5. Vérifiez

Après le démarrage :
- Vérifiez l'API à `http://localhost:48080/swagger-ui.html`
- Créez un appel de test API contre un point de terminaison critique
- Surveillez le journal d'audit pour toutes les erreurs inattendues dans les 15 premières minutes

## Mise à niveau du frontend

```bash
# Operator frontend
cd frontend-operator
npm install
ng build --configuration production
docker compose up -d --build frontend-operator

# Customer frontend
cd ../frontend-customer
npm install
ng build --configuration production
docker compose up -d --build frontend-customer
```

Les frontends sont sans état — les mises à niveau ne nécessitent aucun temps d'arrêt.

## Mises à niveau des contrats intelligents

!!! warning
    Les mises à niveau de contrats intelligents sont les opérations les plus sensibles. Tous les contrats passent par un déploiement et un audit testnet avant toute mise à niveau du réseau principal. Ne mettez jamais à niveau les contrats du réseau principal sans d'abord effectuer la validation du testnet.


### Contrats évolutifs et non évolutifs

| Contrat | Évolutif | Chemin de mise à niveau |
|--------------|------------|-------------|
| `AssetTokenFactory` | Non (usine CREATE2) | Déployer une nouvelle usine, mettre à jour la configuration backend |
| `EwpgTREXFactory` | Non | Déployer une nouvelle usine |
| `IdentityRegistryStorage` | Oui (proxy UUPS) | Mise à niveau de la mise en œuvre du proxy |
| `ModularCompliance` | Oui (proxy UUPS) | Mise à niveau de la mise en œuvre du proxy |
| Contrats de jetons (par émission) | Non | Ne peut pas être mis à niveau après le déploiement |

### Mise à niveau d'un contrat de proxy UUPS

```bash
cd contracts
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow
```

Le script de mise à niveau :
1. Déploie le nouveau contrat de mise en œuvre
2. Appelle `upgradeToAndCall` sur le proxy UUPS
3. Vérifie que la nouvelle implémentation est active

### Mise à niveau des modules de conformité

Les modules de conformité peuvent être ajoutés, supprimés ou remplacés sans mettre à niveau le contrat de jeton lui-même. Il s'agit du chemin de mise à niveau préféré pour les modifications de logique de conformité.

```bash
# Add a new compliance module to a token
curl -X POST http://localhost:48080/api/v1/admin/tokens/{tokenAddress}/compliance/modules \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -H "Content-Type: application/json" \
  -d '{"moduleAddress": "0xNewModuleAddress", "chain": "mainnet"}'
```

## Procédure de restauration

Si une mise à niveau provoque des problèmes, effectuez une restauration en revenant à la balise d'image Docker précédente :

```bash
# Backend rollback
docker compose stop backend
docker tag ghcr.io/ewpg/registerwerk-backend:previous \
  registerwerk-backend:latest
docker compose up -d backend
```
Les migrations Flyway de ce dépôt ne disposent pas de scripts de retour arrière automatiques. Si une version modifie le schéma, restaurez la sauvegarde antérieure à la mise à niveau avec l'image applicative précédente, ou déployez une migration corrective révisée.

## Mise à niveau de Kong

```bash
docker compose stop kong
docker compose pull kong
docker compose up -d kong
```

Après la mise à niveau de Kong, réappliquez la configuration déclarative :

```bash
deck sync --config gateway/kong.yml
```
sidebar_position: 3
---

# Mises à niveau

## Mise à niveau du backend

1. Extrayez une nouvelle image ou créez localement :
   ```bash
   docker build -t registerwerk-backend:v2.0.0 backend/
   ```

2. Mettez à jour la balise d'image dans `docker-compose.yml`

3. Commencez par un redémarrage progressif (Flyway migre automatiquement) :
   ```bash
   docker compose up -d --no-deps backend
   ```

4. Vérifiez l'état : `curl http://localhost:48080/actuator/health`

## Mises à niveau des contrats intelligents

Les modules de conformité prennent en charge la mise à niveau sur place via `UpgradeCompliance.s.sol` :

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```

Les jetons et les contrats d'identité ne sont **pas évolutifs de par leur conception** (l'immuabilité est une exigence légale pour les titres). Les mises à jour nécessitent le déploiement d'une nouvelle suite et la migration des investisseurs.

## Mises à niveau du sous-graphe

Si le schéma du sous-graphe change, conservez la configuration précédente et déployez une nouvelle version. Avant le déploiement, vérifiez que chaque adresse singleton a son propre `*_START_BLOCK_<SUFFIX>` réel et que chaque entrée BondDesk, AMM et RepoVault utilise `address@deploymentBlock`. Un bloc de factory n'est pas un substitut valide aux blocs de déploiement des autres sources.

Effectuez le rendu et la compilation de toutes les cibles configurées sans publier au préalable :

```bash
SUBGRAPH_VALIDATE_ONLY=true ./indexer/evm/deploy-subgraph.sh all
```

Puis déployez avec une étiquette de version qui n'a jamais été utilisée pour les noms de sous-graphe concernés :

```bash
SUBGRAPH_VERSION_LABEL=schema-20260729-01 ./indexer/evm/deploy-subgraph.sh all
```

graph-node réindexe chaque source rendue à partir du bloc configuré de cette source. Conservez les versions précédentes et leur configuration disponibles pour une restauration non destructive jusqu'à ce que chaque remplacement
ait atteint la tête de chaîne et que ses plages d'événements aient été rapprochées indépendamment. Ne supprimez pas
le sous-graphe précédent avant validation ; la restauration signifie redéployer le manifeste
et la configuration source précédemment approuvés sous une autre nouvelle étiquette de version.

## Mises à niveau de Kong

1. Mettez à jour la balise d'image `kong` dans `docker-compose.yml` (et `gateway/docker-compose.kong.yml`
si vous utilisez la pile de passerelle autonome uniquement).
2. Redémarrez Kong : `docker compose restart kong` — il relit `gateway/kong.yml` au démarrage
(mode sans base de données, aucune migration à exécuter).

## Mises à jour des dépendances

- **Java / Spring Boot** : mise à jour de `pom.xml`, exécutez `mvn verify`
- **Angular** : `ng update @angular/core @angular/cli`
- **Contrats** : `forge update` (met à jour les sous-modules git dans `contracts/lib/`)
