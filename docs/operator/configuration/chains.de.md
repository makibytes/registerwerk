---
title: Chain-Konfiguration
---

# Chain-Konfiguration

Das eWpG-Register speichert die Chain-Konfiguration in der Tabelle `chain_config`. Das bedeutet, Sie können zur Laufzeit neue Blockchains hinzufügen, ohne das Backend erneut bereitzustellen.

## Vorkonfigurierte Chains

Die folgenden Chains werden durch die Flyway-Migrationen vorbelegt:

| Bezeichner | Chain | Typ | Chain-ID |
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

## Eine neue EVM-Chain hinzufügen

### Schritt 1 – Über die Admin-API registrieren

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

`BlockchainClientRegistry` des Backends übernimmt die neue Chain bei der nächsten Aktualisierung (alle 60 Sekunden) oder sofort über:

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Schritt 2 – Zu graph-node hinzufügen

Fügen Sie in `indexer/evm/docker-compose.yml` zur Umgebungsvariable `ethereum` hinzu:
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

### Schritt 3 – graph-node neu starten

Laden Sie die neue Netzwerkkonfiguration neu, bevor Sie eine Subgraph-Bereitstellung einreichen:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Warten Sie, bis graph-node fehlerfrei meldet.

### Schritt 4 – Subgraph bereitstellen und indizieren

Konfigurieren Sie jede unter [The Graph](../indexers/the-graph.md) beschriebene statische `*_ARBITRUM`-Quelle, dann:

```bash
SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
```

Die Registrierung einer Backend-Chain entdeckt keine Subgraph-Datenquellen und weist deren Code-Identität nicht nach.
Die resultierenden Entitäten bleiben vorläufige, aus Ereignissen abgeleitete Projektionen.

## FHE-Chains (Fhenix / Inco)

Fhenix- und Inco-Chains verwenden das Zama-fhEVM und unterstützen vertrauliche ERC-3643-Token. Sie sind in V15 vorbelegt. Stellen Sie den `ConfidentialERC3643`-Vertrag bereit mit:

```bash
forge script script/Deploy.s.sol --rpc-url $FHENIX_HELIUM_RPC --broadcast
```

`ConfidentialErc3643Service` des Backends verarbeitet verschlüsselte Übertragungsvorgänge auf diesen Chains.
