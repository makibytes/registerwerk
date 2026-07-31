---
title: Aggiunta di nuove catene
---

# Aggiunta di nuove catene { #adding-new-chains }

I client della catena backend possono essere registrati in fase di runtime. L'indicizzazione EVM richiede anche la configurazione di rete di graph-node
e una destinazione di distribuzione supportata con origini contratto esplicite.

## Tipi di catene supportati { #supported-chain-types }

| Tipo | Esempi |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, qualsiasi rete compatibile con EVM |
| `SOLANA` | Solana Mainnet, Devnet |

## Aggiunta di una catena EVM (procedura dettagliata completa) { #adding-an-evm-chain-full-walkthrough }

### 1. Registra tramite Admin API { #1-register-via-admin-api }

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

### 2. Distribuisci i contratti { #2-deploy-contracts }

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Aggiungi alla configurazione di graph-node { #3-add-to-graph-node-config }

Vedere [Configurazione indicizzatore](../configuration/indexers.md) per le modifiche al TOML e a docker-compose.

### 4. Riavvia graph-node con la nuova rete { #4-restart-graph-node-with-the-new-network }

L'API di amministrazione delle distribuzioni non può accettare un manifest per la nuova rete finché graph-node non ha
ricaricato la configurazione della catena:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Verifica che graph-node sia integro prima di continuare.

### 5. Configura e distribuisci il sottografo { #5-configure-and-deploy-the-subgraph }

Configura ogni sorgente `*_OPTIMISM` descritta in [The Graph](../indexers/the-graph.md), quindi:

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

Il sottografo è una proiezione provvisoria degli eventi. Non stabilisce la finalità della catena, l'effetto legale, lo stato autorevole del registro né l'identità del codice distribuito.

### 6. Attiva l'aggiornamento del client { #6-trigger-client-refresh }

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

`BlockchainClientRegistry` crea immediatamente un nuovo client Web3j per la catena.

## Fallback RPC { #fallback-rpcs }

È possibile configurare più URL RPC per il failover. La configurazione della catena memorizza `fallback_rpc_urls` come elenco separato da virgole. Se l'RPC primario fallisce, il registro tenta i fallback in ordine.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```
