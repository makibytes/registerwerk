---
title: The Graph (indexeur EVM)
---

# The Graph — Indexation EVM

Registerwerk utilise `graph-node` pour créer des projections provisoires dérivées d'événements pour les contrats
EVM configurés. Les entités de sous-graphe ne sont pas des attestations de finalité de chaîne, des entrées de registre juridique, des preuves de règlement juridique ou des preuves d'identité de code déployé. Réconciliez la chaîne configurée, les confirmations, le déploiement du contrat et le registre juridique faisant autorité avant de vous y fier.

## Installer et vérifier

```bash
cd indexer/evm/subgraph
npm install
npm test
```

`npm test` vérifie la parité ABI/événement enregistrée par rapport aux artefacts Forge, teste le rendu du manifeste,
exécute la génération de code Graph et compile chaque mapping.

## Configuration de déploiement requise

La cible de déploiement sélectionne un suffixe d'environnement :

| Cible | Réseau The Graph | Suffixe |
|---|---|---|
| `mainnet` | `mainnet` | `MAINNET` |
| `sepolia` | `sepolia` | `SEPOLIA` |
| `polygon` | `polygon` | `POLYGON` |
| `polygon-amoy` | `polygon-amoy` | `POLYGON_AMOY` |
| `base` | `base` | `BASE` |
| `base-sepolia` | `base-sepolia` | `BASE_SEPOLIA` |
| `arbitrum-one` | `arbitrum-one` | `ARBITRUM` |
| `arbitrum-sepolia` | `arbitrum-sepolia` | `ARBITRUM_SEPOLIA` |
| `avalanche` | `avalanche` | `AVALANCHE` |
| `avalanche-fuji` | `avalanche-fuji` | `AVALANCHE_FUJI` |
| `optimism` | `optimism` | `OPTIMISM` |
| `optimism-sepolia` | `optimism-sepolia` | `OPTIMISM_SEPOLIA` |

Pour chaque suffixe, configurez les quatre sources singleton ci-dessous. Leur bloc de démarrage vaut zéro par défaut, mais les opérateurs doivent toujours utiliser le bloc de déploiement réel pour rendre explicite la portée de la relecture. Chaque source a une provenance indépendante : ne copiez pas le bloc d'une factory dans les autres champs source, sauf si les reçus de déploiement prouvent réellement qu'ils partagent ce bloc.

```dotenv
ASSET_TOKEN_FACTORY_ADDRESS_SEPOLIA=0x...
ASSET_TOKEN_FACTORY_START_BLOCK_SEPOLIA=120
REPO_MARKET_FACTORY_ADDRESS_SEPOLIA=0x...
REPO_MARKET_FACTORY_START_BLOCK_SEPOLIA=130
DVP_SETTLEMENT_ADDRESS_SEPOLIA=0x...
DVP_SETTLEMENT_START_BLOCK_SEPOLIA=140
CONFIDENTIAL_FACTORY_ADDRESS_SEPOLIA=0x...
CONFIDENTIAL_FACTORY_START_BLOCK_SEPOLIA=150
```

Les déploiements BondDesk, Stablecoin AMM et RepoVault ne sont pas détectables de manière fiable via une factory. Listez chaque instance explicitement sous la forme `address@deploymentBlock`, séparée par des virgules :

```dotenv
BOND_DESK_INSTANCES_SEPOLIA=0xDesk1@123,0xDesk2@456
STABLECOIN_AMM_INSTANCES_SEPOLIA=0xAmm1@123,0xAmm2@456
REPO_VAULT_INSTANCES_SEPOLIA=0xVault1@123,0xVault2@456
```

Si l'opérateur configure zéro instance pour un rôle, définissez sa liste sur exactement `NONE`. Il s'agit d'une assertion de l'opérateur concernant la configuration, et non d'une preuve qu'aucun déploiement n'existe on-chain. Une liste non définie ou vide se voit rejetée par défaut (fail closed). Le moteur de rendu rejette également les adresses nulles, les blocs mal formés et les adresses réutilisées par toute autre source statique.

## Déployer

```bash
SUBGRAPH_VERSION_LABEL=sepolia-20260729-01 ./indexer/evm/deploy-subgraph.sh sepolia
```

Utilisez `SUBGRAPH_VALIDATE_ONLY=true` pour effectuer le rendu, générer et compiler sans soumettre de déploiement graph-node. `all` traite chaque cible de la table et nécessite donc une configuration pour chaque suffixe. Un véritable déploiement nécessite également `SUBGRAPH_VERSION_LABEL` ; choisissez une nouvelle étiquette pour chaque déploiement sur ce nom de graphe. Le wrapper rejette une étiquette absente ; l'opérateur doit s'assurer que l'étiquette fournie est neuve. Conservez la version précédente disponible jusqu'à ce que le remplacement ait rattrapé son retard et réussi le rapprochement indépendant des plages d'événements.

L'AssetTokenFactory crée des sources de données de jetons dynamiques à partir de `TokenDeployed` et `VaultDeployed`. Le RepoMarketFactory crée de la même façon des sources RepoMarket à partir de `MarketCreated`. Les nouvelles instances des trois types de contrat explicitement répertoriés nécessitent une mise à jour de la liste et un redéploiement de sous-graphe. Les adresses émises par la factory, les ID d'actifs, les références de jetons, les paramètres oracle et les blocs d'observation sont stockés en tant qu'attestations d'événement. Ils ne vérifient pas le bytecode déployé, la provenance du déploiement ou le lien vers un enregistrement de base de données d'application.

## Migration et relecture des projections

Les entités de notionnel propriétaire/slot ERC-3525 et de cycle de vie des requêtes ERC-7540 nécessitent l'ordre des événements à partir du déploiement du contrat. Les lignes `HolderBalance` existantes pour ERC-3525 comptent des ID de jeton et ne peuvent pas être converties en notionnel. Ne les copiez pas dans `Erc3525OwnerSlotBalance`.

Pour cette révision de schéma, déployez une nouvelle version de sous-graphe et rejouez chaque source à partir de son bloc de déploiement réel. Une projection `INCOMPLETE` ne peut pas reconstruire les propriétaires, les emplacements, les valeurs, les types de requêtes manquants ou la configuration antérieure du marché RepoVault. Chaque projection RepoVault reste `INCOMPLETE` à moins que la provenance du déploiement et la relecture complète ne soient prouvées en dehors de ce sous-graphe ; le simple fait d'observer le premier événement à une adresse statique configurée ne fournit pas cette preuve. Conservez l'ancien déploiement disponible pour l'annulation jusqu'à ce que la nouvelle projection atteigne la tête de chaîne et ait été rapprochée indépendamment.

Les montants RepoVault `Allocated` et `Deallocated` ne sont projetés que comme un flux de trésorerie net signé. La désallocation peut dépasser l'allocation antérieure en raison d'intérêts ou de réalisation de pertes, donc cette valeur n'est pas un capital impayé, une position de marché mise à l'échelle ou une NAV, et un total négatif n'est pas en soi une incohérence.

## Surveiller et interroger

```bash
curl -s http://localhost:8030/graphql \
  -d '{"query":"{indexingStatuses{subgraph synced health chains{network latestBlock{number}}}}"}' \
  | jq '.data.indexingStatuses[]'
```

`synced: true` décrit uniquement la progression de graph-node ; ce n'est pas un signal de finalité ou d'effet juridique.
Interrogez un déploiement sur `http://localhost:8000/subgraphs/name/<subgraph-name>`.

Les échecs courants sont la limitation de débit RPC, la mémoire insuffisante, les artefacts Forge obsolètes, la dérive d'ABI ou une configuration de source statique manquante. Exécutez `npm test` avant de diagnostiquer un échec de déploiement.
