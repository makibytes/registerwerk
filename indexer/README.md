# eWpG Registry — Token Indexers

Dieses Verzeichnis enthält die Setup-Konfigurationen für die optionalen Indexer-Sidecar-Stacks
(`evm/`, `solana/`, `canton/`, `starknet/`, `stellar/`), die alle emittierten Token auf allen
angebundenen Blockchains überwachen. Starknet und Stellar benötigen dabei **keinen** externen
Sidecar-Dienst wie graph-node oder Yellowstone — der Backend-Service spricht direkt per
JSON-RPC bzw. Horizon-REST mit der Chain; `indexer/starknet/` und `indexer/stellar/` stellen
nur optionale lokale Entwicklungsknoten bereit (siehe deren jeweilige READMEs).

## Architektur

```
                ┌─────────────────────────────────────────────────┐
                │            eWpG Registry Backend                 │
                │                                                   │
                │  GraphNodeSyncService  (poll alle 30s)           │
                │  SolanaTransferSyncService  (WebSocket + Poll)   │
                │  CantonTransferSyncService  (Ledger-API-Stream)  │
                │  StarknetTransferSyncService  (poll alle 30s)    │
                │  StellarTransferSyncService  (poll alle 30s)     │
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

    Starknet und Stellar (kein Sidecar dazwischen — direktes RPC/Horizon-Polling):

                Starknet-RPC  ◄── starknet_getEvents ──  StarknetTransferSyncService
                Horizon (Stellar)  ◄── /payments cursor ──  StellarTransferSyncService
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
5. graph-node neu starten, damit die neue Chain-Konfiguration vor dem Deployment geladen ist:
   ```bash
   docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
   ```
6. Alle statischen Quellen mit ihrem jeweiligen Deployment-Block konfigurieren und mit einem
   eindeutigen Versionslabel deployen:
   ```bash
   export ASSET_TOKEN_FACTORY_ADDRESS_ARBITRUM=0x...
   export ASSET_TOKEN_FACTORY_START_BLOCK_ARBITRUM=123
   export REPO_MARKET_FACTORY_ADDRESS_ARBITRUM=0x...
   export REPO_MARKET_FACTORY_START_BLOCK_ARBITRUM=124
   export DVP_SETTLEMENT_ADDRESS_ARBITRUM=0x...
   export DVP_SETTLEMENT_START_BLOCK_ARBITRUM=125
   export CONFIDENTIAL_FACTORY_ADDRESS_ARBITRUM=0x...
   export CONFIDENTIAL_FACTORY_START_BLOCK_ARBITRUM=126
   export BOND_DESK_INSTANCES_ARBITRUM=NONE
   export STABLECOIN_AMM_INSTANCES_ARBITRUM=NONE
   export REPO_VAULT_INSTANCES_ARBITRUM=NONE
   SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
   ```

   `NONE` ist eine Betreiberangabe über null konfigurierte Instanzen, keine On-Chain-Erkennung.
   Für vorhandene BondDesk-, AMM- oder RepoVault-Instanzen gilt das Format
   `address@deploymentBlock`. Die vollständige Variablenliste steht in
   `docs/operator/indexers/the-graph.md`.

### Starten

```bash
# graph-node + IPFS + PostgreSQL hochfahren
docker compose -f indexer/evm/docker-compose.yml up -d

# Jede Singleton-Quelle braucht Adresse und eigenen Deployment-Block; Listen verwenden
# address@deploymentBlock oder exakt NONE. Details: docs/operator/indexers/the-graph.md
export ASSET_TOKEN_FACTORY_ADDRESS_SEPOLIA=0x...
export ASSET_TOKEN_FACTORY_START_BLOCK_SEPOLIA=123
export REPO_MARKET_FACTORY_ADDRESS_SEPOLIA=0x...
export REPO_MARKET_FACTORY_START_BLOCK_SEPOLIA=124
export DVP_SETTLEMENT_ADDRESS_SEPOLIA=0x...
export DVP_SETTLEMENT_START_BLOCK_SEPOLIA=125
export CONFIDENTIAL_FACTORY_ADDRESS_SEPOLIA=0x...
export CONFIDENTIAL_FACTORY_START_BLOCK_SEPOLIA=126
export BOND_DESK_INSTANCES_SEPOLIA=NONE
export STABLECOIN_AMM_INSTANCES_SEPOLIA=NONE
export REPO_VAULT_INSTANCES_SEPOLIA=NONE
SUBGRAPH_VERSION_LABEL=sepolia-20260729-01 ./indexer/evm/deploy-subgraph.sh sepolia
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

## Starknet: JSON-RPC `starknet_getEvents`-Polling

**Kein Sidecar-Dienst nötig** — der Backend-`StarknetTransferSyncService` spricht alle 30s
direkt per JSON-RPC mit der konfigurierten Starknet-Chain (`starknet_getEvents`, gefiltert auf
den Standard-Cairo-`Transfer`-Event-Selector) und verfolgt den zuletzt synchronisierten Block
in `indexer_state` (`STARKNET_POLL`). Beobachtete Contract-Adressen kommen aus
`AssetDeployment.contractAddress` — bereits zum Deploy-Zeitpunkt per UDC-Adress-Precomputation
gesetzt, keine separate On-Chain-Bestätigung nötig.

### Starten (lokaler Devnet, optional)

```bash
docker compose -f indexer/starknet/docker-compose.yml up -d
```

Details siehe `indexer/starknet/README.md`.

---

## Stellar: Horizon `/payments`-Cursor-Polling

**Kein Sidecar-Dienst nötig** — der Backend-`StellarTransferSyncService` pollt alle 30s
Horizons `/accounts/{issuer}/payments`-Endpunkt (cursor-basiert) für Zahlungen, die den
verfolgten Asset-Code/Issuer treffen, und verfolgt den Horizon-Paging-Token in `indexer_state`
(`STELLAR_HORIZON`). Der Issuer-Account kommt aus `AssetDeployment.contractAddress`
(gesetzt beim Issuance, synchron verfügbar aus der Wallet). **Bekannte Einschränkung**: nur
Zahlungen, die den Issuer-Account berühren (Emission, Rücknahme, darüber geroutete Transfers),
werden erfasst — reine Holder-zu-Holder-Transfers ohne Issuer-Beteiligung noch nicht.

### Starten (lokaler Devnet, optional)

```bash
docker compose -f indexer/stellar/docker-compose.yml up -d
```

Details siehe `indexer/stellar/README.md`.

---

## Resilienz-Konzept

| Komponente | Was passiert bei Ausfall | Wie wird aufgeholt |
|---|---|---|
| graph-node | Stoppt die Indexierung | Bei Neustart setzt graph-node am letzten indexierten Block fort |
| EVM-RPC-Node | graph-node verliert Verbindung | `GRAPH_ETHEREUM_REQUEST_RETRIES=10`, Fallback-RPCs konfigurierbar |
| Backend ↔ graph-node Sync | Backend kann graph-node nicht erreichen | `indexer_state.consecutive_errors` zählt hoch; nach Reconnect wird von `last_synced_block` nachgeholt |
| Yellowstone gRPC Stream | Stream bricht ab | Backend reconnectet; paralleler Polling-Job (`SOLANA_POLL`) füllt Lücken |
| Solana-RPC | Polling schlägt fehl | `indexer_state.status = ERROR`; `IndexerMonitorService` warnt nach 2h |
| Starknet-RPC | Polling schlägt fehl | `indexer_state.consecutive_errors` zählt hoch (max. 10) → `status = ERROR`; nächster Poll setzt am letzten `last_synced_block` fort |
| Stellar-Horizon | Polling schlägt fehl | Wie Starknet; Cursor ist der Horizon-Paging-Token in `last_synced_signature` |

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

---

## Abdeckung pro Chain

| Chain | Transfer-Indexierung | Mechanismus |
|---|---|---|
| EVM (Ethereum, Polygon, Base, …) | ✓ | The Graph (`indexer/evm`) |
| Solana | ✓ | Yellowstone gRPC + Polling (`indexer/solana`) |
| Canton | ✓ | Ledger-API-Stream (`indexer/canton`, `CantonTransferSyncService`) |
| Starknet | ✓ | JSON-RPC `starknet_getEvents`-Polling (`indexer/starknet`, `StarknetTransferSyncService`) |
| Stellar | ✓ (teilweise) | Horizon `/payments`-Cursor-Polling (`indexer/stellar`, `StellarTransferSyncService`) — erfasst nur Zahlungen, die den Issuer-Account berühren; reine Holder-zu-Holder-Transfers noch nicht. |

`HolderDataService` (Holder-Sync) sieht jede Chain, sobald `token_transfer`-Einträge für sie
existieren — für Starknet und Stellar ist das jetzt der Fall, seit `AssetDeployment.contractAddress`
beim Deploy/Issuance direkt gesetzt wird (UDC-Adress-Precomputation bzw. Issuer-G-Address) statt
wie zuvor dauerhaft leer zu bleiben.
