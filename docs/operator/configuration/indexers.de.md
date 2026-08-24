---
title: Indexer-Konfiguration
---

# Indexer-Konfiguration

## EVM-Indexer (graph-node)

graph-node wird über `indexer/evm/config/graph-node.toml` konfiguriert. Die TOML-Datei definiert, welche Chains indiziert werden und welche RPC-Anbieter verwendet werden.

### Eine Chain zu graph-node hinzufügen

1. Fügen Sie die RPC-URL zu `.env` hinzu:
   ```dotenv
   ARBITRUM_RPC=https://arb1.arbitrum.io/rpc
   ```

2. Fügen Sie sie in `indexer/evm/docker-compose.yml` zur Umgebungsvariable `ethereum` hinzu:
   ```
   ,arbitrum-one:${ARBITRUM_RPC}
   ```

3. Fügen Sie sie zu `indexer/evm/config/graph-node.toml` hinzu:
   ```toml
   [chains.arbitrum-one]
   shard = "primary"
   protocol = "ethereum"
   [[chains.arbitrum-one.provider]]
   url = "${ARBITRUM_RPC}"
   features = []
   ```

4. Starten Sie graph-node neu, damit es das neue Netzwerk lädt, und warten Sie, bis es fehlerfrei meldet:

   ```bash
   docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
   ```

5. Konfigurieren Sie jede statische Quelle mit dem Suffix `ARBITRUM` und stellen Sie mit einer eindeutigen
   Versionsbezeichnung bereit:

   ```bash
   SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
   ```

   Siehe [The Graph](../indexers/the-graph.md) für Singleton-Variablen, Multi-Instanz-Listen und
   Anforderungen an den Bereitstellungsblock. Diese Ausgaben sind vorläufige Ereignisprojektionen, keine
   Chain-Finalität, kein maßgeblicher Registerstatus, kein Abwicklungsnachweis und keine Verifizierung der Code-Identität.

### Retry-Konfiguration

`GRAPH_ETHEREUM_REQUEST_RETRIES=10` ist standardmäßig gesetzt. graph-node wiederholt fehlgeschlagene RPC-Aufrufe, bevor der Indexer als fehlgeschlagen markiert wird.

## Solana-Indexer (Yellowstone)

Yellowstone wird über `indexer/solana/config/yellowstone.toml` konfiguriert.

```toml
[grpc]
address = "0.0.0.0:10000"

[upstream]
endpoint = "${YELLOWSTONE_UPSTREAM_ENDPOINT}"
token = "${YELLOWSTONE_TOKEN}"
```

Setzen Sie `YELLOWSTONE_UPSTREAM_ENDPOINT` auf einen Geyser-fähigen Solana-RPC-Endpunkt.

## Backend-Sync-Konfiguration

In `application.yml`:

```yaml
ewpg:
  indexer:
    graph-node-poll-interval: 30s    # How often to query The Graph
    solana-poll-interval: 10m        # Fallback polling for Solana
    stale-threshold: 2h              # Alert if no sync for this long
```
