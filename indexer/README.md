# eWpG Registry — Token Indexers

Dieses Verzeichnis enthält die Setup-Konfigurationen für die beiden Indexer-Stacks,
die alle emittierten Token auf allen angebundenen Blockchains überwachen.

## Architektur

```
                ┌─────────────────────────────────────────────────┐
                │            eWpG Registry Backend                 │
                │                                                   │
                │  GraphNodeSyncService  (poll alle 30s)           │
                │  SolanaTransferSyncService  (WebSocket + Poll)   │
                │  IndexerMonitorService  (Health-Check alle 5min) │
                │                                                   │
                │  → speichert alle Events in token_transfer       │
                │  → verfolgt Cursor in indexer_state              │
                └──────┬─────────────────────────┬─────────────────┘
                       │                         │
          ┌────────────▼──────────┐   ┌──────────▼──────────┐
          │   The Graph           │   │  Yellowstone gRPC    │
          │   (graph-node)        │   │  (Solana Geyser)     │
          │   für alle EVM-Chains │   │  für Solana          │
          └────────────┬──────────┘   └──────────┬───────────┘
                       │                         │
          ┌────────────▼──────────────────────────▼───────────┐
          │  Ethereum  Polygon  Base  …weitere EVM-Chains      │
          │                                    Solana          │
          └────────────────────────────────────────────────────┘
```

## EVM-Chains: The Graph (graph-node)

**Warum The Graph?**
- Bewährter Open-Source-Indexer für alle EVM-kompatiblen Chains
- Automatische Lückenerkennung: graph-node speichert den letzten indexierten Block und setzt nach einem Neustart exakt dort fort
- **Auto-Registrierung neuer Token**: Sobald der `AssetTokenFactory`-Contract einen `TokenDeployed`-Event emittiert, instanziiert der Subgraph automatisch einen neuen Data Source für den neuen Token-Contract — keine manuelle Intervention nötig

### Neue EVM-Chain hinzufügen

1. RPC-Endpunkt in `.env` hinzufügen, z.B. `ARBITRUM_RPC=https://...`
2. In `indexer/evm/docker-compose.yml` beim `ethereum`-Env-Var ergänzen:
   ```
   ,arbitrum-one:${ARBITRUM_RPC}
   ```
3. In `indexer/evm/config/graph-node.toml` Block hinzufügen:
   ```toml
   [chains.arbitrum-one]
   shard = "primary"
   protocol = "ethereum"
   [[chains.arbitrum-one.provider]]
   url = "${ARBITRUM_RPC}"
   ```
4. Via Admin-API neue Chain in Backend registrieren:
   ```http
   POST /api/v1/admin/chains
   { "identifier": "ARBITRUM_MAINNET", "chainType": "EVM", "networkType": "MAINNET",
     "chainId": 42161, "rpcUrl": "...", "blockExplorerUrl": "https://arbiscan.io",
     "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
     "graphSubgraphName": "ewpg/arbitrum-mainnet" }
   ```
5. Subgraph deployen:
   ```bash
   FACTORY_ADDRESS_ARBITRUM=0x... ./indexer/evm/deploy-subgraph.sh arbitrum-one
   ```

### Starten

```bash
# graph-node + IPFS + PostgreSQL hochfahren
docker compose -f indexer/evm/docker-compose.yml up -d

# Subgraphs deployen (AssetTokenFactory-Adresse pro Chain nötig)
FACTORY_ADDRESS_SEPOLIA=0xYourFactory ./indexer/evm/deploy-subgraph.sh sepolia
```

### GraphQL-Abfragen (direkt am graph-node)

```bash
# Alle Transfers eines Tokens
curl -X POST http://localhost:8000/subgraphs/name/ewpg/ethereum-sepolia \
  -H "Content-Type: application/json" \
  -d '{"query": "{ transfers(where:{token:\"0xtoken\"} orderBy:blockNumber) { id from to amount eventType transactionHash blockTimestamp } }"}'
```

---

## Solana: Yellowstone Dragon's Mouth (gRPC Geyser)

**Warum Yellowstone?**
- Open-Source gRPC-Proxy für Solana's Geyser Plugin Interface
- Echtzeit-Streaming von Transaktionen (wesentlich schneller als Polling)
- Der Backend-`SolanaTransferSyncService` subscribt via gRPC auf SPL-Token-Transactions

**Resilienz-Fallback**: Ein zusätzlicher `@Scheduled`-Job ruft alle 10 Minuten `getSignaturesForAddress` für jeden bekannten SPL-Mint auf und schließt damit Lücken, die beim gRPC-Ausfall entstanden sind.

### Starten

```bash
# Yellowstone-Proxy hochfahren
docker compose -f indexer/solana/docker-compose.yml up -d
```

Yellowstone benötigt einen Upstream-Endpunkt, der das Geyser-Plugin unterstützt
(z.B. Helius, Triton, oder ein selbst betriebener Validator mit Yellowstone-Plugin).
`YELLOWSTONE_UPSTREAM_ENDPOINT` in `.env` setzen.

---

## Resilienz-Konzept

| Komponente | Was passiert bei Ausfall | Wie wird aufgeholt |
|---|---|---|
| graph-node | Stoppt die Indexierung | Bei Neustart setzt graph-node am letzten indexierten Block fort |
| EVM-RPC-Node | graph-node verliert Verbindung | `GRAPH_ETHEREUM_REQUEST_RETRIES=10`, Fallback-RPCs konfigurierbar |
| Backend ↔ graph-node Sync | Backend kann graph-node nicht erreichen | `indexer_state.consecutive_errors` zählt hoch; nach Reconnect wird von `last_synced_block` nachgeholt |
| Yellowstone gRPC Stream | Stream bricht ab | Backend reconnectet; paralleler Polling-Job (`SOLANA_POLL`) füllt Lücken |
| Solana-RPC | Polling schlägt fehl | `indexer_state.status = ERROR`; `IndexerMonitorService` warnt nach 2h |

Der `IndexerMonitorService` im Backend prüft alle 5 Minuten, ob `indexer_state.last_synced_at`
älter als 2 Stunden ist, und publiziert ein `INDEXER_STALE`-Audit-Event.

---

## Token-Historie im Backend

Alle indexierten Events werden in der `token_transfer`-Tabelle gespeichert.
REST-API:

```
GET /api/v1/assets/{assetId}/history
GET /api/v1/assets/{assetId}/deployments/{deploymentId}/history
GET /api/v1/assets/{assetId}/deployments/{deploymentId}/history/summary
```

Jeder Eintrag enthält: `txHash`, `explorerTxUrl` (direkter Link zum Block-Explorer),
`from`, `to`, `amount`, `eventType` (MINT/TRANSFER/BURN), `occurredAt`.
