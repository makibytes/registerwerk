---
title: Configurazione della catena
---

# Configurazione della catena { #chain-configuration }

Il registro eWpG memorizza la configurazione della catena nella tabella `chain_config`. Ciò significa che puoi aggiungere nuove blockchain in fase di esecuzione senza ridistribuire il backend.

## Catene preconfigurate { #pre-configured-chains }

Le seguenti catene vengono seminate dalle migrazioni Flyway:

| Identificatore | Catena | Tipo | ID catena |
|---|---|---|---|
| ETHEREUM_MAINNET | Ethereum | EVM Mainnet | 1 |
| ETHEREUM_SEPOLIA | Ethereum Sepolia | EVM Testnet | 11155111 |
| POLYGON_MAINNET | Polygon | EVM Mainnet | 137 |
| POLYGON_AMOY | Polygon Amoy | EVM Testnet | 80002 |
| BASE_MAINNET | Base | EVM Mainnet | 8453 |
| BASE_SEPOLIA | Base Sepolia | EVM Testnet | 84532 |
| SOLANA_MAINNET | Solana | Solana Mainnet | — |
| SOLANA_DEVNET | Solana Devnet | Solana Testnet | — |
| FHENIX_MAINNET | Fhenix | EVM Mainnet (FHE) | 21888 |
| FHENIX_HELIUM | Fhenix Helium | EVM Testnet (FHE) | 8008135 |
| INCO_MAINNET | Inco | EVM Mainnet (FHE) | 9090 |
| INCO_RIVEST | Inco Rivest | EVM Testnet (FHE) | 21097 |

## Aggiunta di una nuova catena EVM { #adding-a-new-evm-chain }

### Passaggio 1: registrazione tramite Admin API { #step-1-register-via-admin-api }

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "ARBITRUM_MAINNET",
    "displayName": "Arbitrum One",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 42161,
    "rpcUrl": "https://arb1.arbitrum.io/rpc",
    "wsUrl": "wss://arb1.arbitrum.io/ws",
    "blockExplorerUrl": "https://arbiscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/arbitrum-mainnet"
  }'
```

`BlockchainClientRegistry` del backend rileva la nuova catena al successivo aggiornamento (ogni 60 secondi) o immediatamente tramite:

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Passaggio 2: aggiungi a graph-node { #step-2-add-to-graph-node }

In `indexer/evm/docker-compose.yml`, aggiungi alla variabile d'ambiente `ethereum`:
```
,arbitrum-one:${ARBITRUM_RPC}
```

In `indexer/evm/config/graph-node.toml`:
```toml
[chains.arbitrum-one]
shard = "primary"
protocol = "ethereum"
[[chains.arbitrum-one.provider]]
url = "${ARBITRUM_RPC}"
features = []
```

### Passaggio 3: riavviare graph-node { #step-3-restart-graph-node }

Ricaricare la nuova configurazione di rete prima di inviare una distribuzione del sottografo:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Attendi che graph-node risulti integro.

### Passaggio 4: distribuire e indicizzare il sottografo { #step-4-deploy-and-index-subgraph }

Configurare ogni sorgente statica `*_ARBITRUM` descritta in [The Graph](../indexers/the-graph.md), quindi:

```bash
SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
```

La registrazione di una catena backend non scopre le origini dati dei sottografi né dimostra la loro identità di codice.
Le entità risultanti rimangono proiezioni provvisorie derivate da eventi.

## Le catene FHE (Fhenix / Inco) { #fhe-chains-fhenix-inco }

Fhenix e le catene Inco utilizzano Zama fhEVM e supportano i token ERC-3643 confidenziali. Sono pre-seminati in V15. Distribuisci il contratto `ConfidentialERC3643` utilizzando:

```bash
forge script script/Deploy.s.sol --rpc-url $FHENIX_HELIUM_RPC --broadcast
```

`ConfidentialErc3643Service` del backend gestisce le operazioni di trasferimento crittografate su queste catene.
