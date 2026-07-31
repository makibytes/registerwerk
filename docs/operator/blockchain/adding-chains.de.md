---
title: Neue Ketten hinzufügen
---

# Neue Ketten hinzufügen

Backend-Chain-Clients können zur Laufzeit registriert werden. Die EVM-Indizierung erfordert außerdem eine Graph-Node-Netzwerkkonfiguration
und ein unterstütztes Bereitstellungsziel mit expliziten Vertragsquellen.

## Unterstützte Chain-Typen

| Typ | Beispiele |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, jede EVM-kompatible Chain |
| `SOLANA` | Solana Mainnet, Devnet |

## Eine EVM-Chain hinzufügen (vollständige Anleitung)

### 1. Über die Admin-API registrieren

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "OPTIMISM_MAINNET",
    "displayName": "Optimism",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 10,
    "rpcUrl": "https://mainnet.optimism.io",
    "wsUrl": "wss://mainnet.optimism.io",
    "blockExplorerUrl": "https://optimistic.etherscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/optimism-mainnet"
  }'
```

### 2. Verträge bereitstellen

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Zur Graph-Node-Konfiguration hinzufügen

Die TOML- und docker-compose-Änderungen finden Sie unter [Indexer-Konfiguration](../configuration/indexers.md).

### 4. Graph-Node mit dem neuen Netzwerk neu starten

Die Deployment-Admin-API kann für das neue Netzwerk erst ein Manifest entgegennehmen, wenn Graph-Node
seine Kettenkonfiguration neu geladen hat:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Prüfen Sie, ob Graph-Node fehlerfrei läuft, bevor Sie fortfahren.

### 5. Subgraph konfigurieren und bereitstellen

Konfigurieren Sie jede unter [The Graph](../indexers/the-graph.md) beschriebene `*_OPTIMISM`-Quelle, dann:

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

Der Subgraph ist eine vorläufige Ereignisprojektion. Er begründet weder Chain-Finalität, rechtliche
Wirkung, maßgeblichen Registerstatus noch die Identität des bereitgestellten Codes.

### 6. Client-Aktualisierung auslösen

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

`BlockchainClientRegistry` erstellt sofort einen neuen Web3j-Client für die Chain.

## Fallback-RPCs

Sie können mehrere RPC-URLs für Failover konfigurieren. Die Chain-Konfiguration speichert `fallback_rpc_urls` als durch Kommas getrennte Liste. Fällt der primäre RPC aus, versucht die Registrierung die Fallbacks der Reihe nach.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```
