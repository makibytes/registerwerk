---
title: Solana (gRPC de Yellowstone)
---

# Indexeur Solana — Yellowstone gRPC

L'indexeur Solana utilise **Yellowstone**, un plugin Geyser qui diffuse les mises à jour de compte et les transactions en temps réel via gRPC. Une solution de secours d'interrogation couvre les périodes pendant lesquelles le flux gRPC n'est pas disponible.

## Architecture

```
Solana Validator (with Yellowstone Geyser plugin)
        |
        | (gRPC stream)
        v
  indexer/solana (Node.js service)
        |
        | (REST / WebSocket)
        v
  Backend Spring Boot
```

## Conditions préalables

- Un point de terminaison Solana RPC avec prise en charge de **Yellowstone gRPC**
- Options hébergées : [Helius](https://helius.dev), [Triton](https://triton.one)
- Auto-hébergé : nécessite un validateur Solana avec le plugin Yellowstone installé

## Configuration

`indexer/solana/yellowstone.yaml`:

```yaml
endpoint: "${SOLANA_YELLOWSTONE_ENDPOINT}"
x_token: "${SOLANA_YELLOWSTONE_TOKEN}"

subscriptions:
  - type: transaction
    accountInclude:
      - "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"  # SPL Token program
    commitment: confirmed

polling:
  enabled: true
  fallback_rpc: "${SOLANA_MAINNET_RPC_URL}"
  poll_interval_seconds: 2
  max_blocks_per_poll: 10
```

Défini dans `.env` :

```bash
SOLANA_YELLOWSTONE_ENDPOINT=https://your-yellowstone-endpoint.helius.xyz:2083
SOLANA_YELLOWSTONE_TOKEN=your_api_token
SOLANA_MAINNET_RPC_URL=https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
SOLANA_DEVNET_RPC_URL=https://devnet.helius-rpc.com/?api-key=YOUR_KEY
```

## Démarrage de l'indexeur Solana

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Ou pour le développement local :

```bash
cd indexer/solana && npm install
npm start
```

## Suivi des jetons SPL

L'indexeur Solana suit les événements du programme de jetons SPL :
- `Transfer` — transferts de jetons entre comptes
- `MintTo` — émission de jetons (mint)
- `Burn` — destruction de jetons (burning)

Il ne filtre que les événements impliquant des adresses de mint de jetons enregistrées dans le backend. L'activité du jeton SPL non liée est ignorée.

## Repli d'interrogation

Si le flux gRPC de Yellowstone se déconnecte ou devient indisponible, l'indexeur passe automatiquement en mode d'interrogation :

1. Interroge `getSignaturesForAddress` pour le programme de jeton SPL à l'intervalle configuré
2. Récupère les détails complets de la transaction pour chaque nouvelle signature
3. Analyse les événements de jeton des journaux de transactions
4. Revient à gRPC dès que le flux se reconnecte

Le repli d'interrogation est moins efficace mais garantit qu'aucun événement n'est manqué lors des pannes du fournisseur.

## Surveillance

L'indexeur Solana expose un point de terminaison d'intégrité :

```bash
curl http://localhost:3001/health
```

Réponse :

```json
{
  "status": "healthy",
  "mode": "grpc",
  "latestSlot": 285614923,
  "lastEventAt": "2025-04-06T12:00:00Z"
}
```

Si `mode` affiche `polling`, le flux gRPC est en panne et la solution de repli est active. Examinez le point de terminaison de Yellowstone.

## Ajout de Solana Devnet

L'indexeur devnet s'exécute en tant qu'instance distincte :

```bash
SOLANA_NETWORK=devnet \
  SOLANA_YELLOWSTONE_ENDPOINT=https://devnet-yellowstone.example.com:2083 \
  SOLANA_YELLOWSTONE_TOKEN=your_devnet_token \
  SOLANA_DEVNET_RPC_URL=https://api.devnet.solana.com \
  npm start
```

Ou ajoutez un deuxième service dans le fichier Docker Compose avec `SOLANA_NETWORK=devnet`.

# Solana — Indexeur Yellowstone gRPC

Les transferts Solana sont surveillés via [Yellowstone Dragon's Mouth](https://github.com/rpcpool/yellowstone-grpc), un proxy gRPC pour l'interface Geyser Plugin de Solana.

## Démarrage de Yellowstone

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Définissez `YELLOWSTONE_UPSTREAM_ENDPOINT` sur un point de terminaison compatible Geyser :
- [Helius](https://helius.dev)
- [Triton One](https://triton.one)
- Un validateur Solana auto-hébergé avec le plugin Yellowstone

## Comment ça marche

`SolanaTransferSyncService` dans le backend :
1. Ouvre un abonnement gRPC à Yellowstone au démarrage (`@PostConstruct`)
2. Filtre les transactions impliquant toute adresse de mint SPL connue
3. Analyse les transferts et les upserts dans `token_transfer`

Si le flux gRPC est interrompu, le service se reconnecte automatiquement.

## Repli d'interrogation

Un job `@Scheduled` séparé s'exécute toutes les 10 minutes :
- Appelle `getSignaturesForAddress` pour chaque mint SPL connu
- Comble toutes les lacunes causées par les temps d'arrêt du flux
- La déduplication via la contrainte UNIQUE empêche le double comptage

## Enregistrement d'un nouveau jeton SPL

Lorsqu'un déploiement d'actifs Solana est créé via l'API, le backend commence automatiquement à surveiller son adresse de mint. Aucune configuration manuelle nécessaire.
