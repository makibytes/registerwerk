---
title: Resilienz und Wiederherstellung
---

# Indexer-Resilienz

Diese Seite beschreibt, wie das Register Indexer-Lücken erkennt, sich nach Ausfällen erholt und vorläufige Ereignisbereiche vergleicht. Diese Verfahren begründen weder Chain-Finalität noch rechtliche Korrektheit.

## Indexer-Statusverfolgung

Das Backend führt eine Tabelle `indexer_state`, die für jede Chain den zuletzt erfolgreich indizierten Block festhält:

```sql
SELECT chain_id, network_name, latest_indexed_block,
       chain_head_block,
       (chain_head_block - latest_indexed_block) AS lag_blocks,
       last_updated_at
FROM indexer_state
ORDER BY lag_blocks DESC;
```

Das Backend fragt alle 30 Sekunden die Indexierungsstatus-API von graph-node ab und aktualisiert diese Tabelle.

## Lückenerkennung

Eine Lücke entsteht, wenn der Indexer hinter den Chain-Head zurückfällt. Das Backend klassifiziert die Verzögerung wie folgt:

| Verzögerung (Blöcke) | Status | Aktion |
|-------------|--------|--------|
| 0–5 | OK | Normalbetrieb |
| 6–20 | WARN | Warnung protokolliert, Prometheus-Alarm wird ausgelöst |
| 21–100 | DEGRADED | Dashboard zeigt eine Warnung, Betreiber-E-Mail wird gesendet |
| 100+ | CRITICAL | `/actuator/health` liefert `DOWN`, PagerDuty-Alarm wird ausgelöst |

## Wiederherstellungsverfahren

### Graph-Node-Wiederherstellung (EVM)

Fällt graph-node wegen RPC-Ausfallzeit zurück:

1. Prüfen Sie die graph-node-Protokolle auf Fehler:

   ```bash
   docker compose logs --tail=100 graph-node | grep -i "error\|panic"
   ```

2. Stellen Sie sicher, dass der RPC-Endpunkt erreichbar ist:

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

3. Ist der RPC ausgefallen, aktualisieren Sie `.env` mit einem Fallback-RPC und starten Sie neu:

   ```bash
   docker compose restart graph-node
   ```

4. Überwachen Sie den Wiederherstellungsfortschritt unter `http://localhost:8030`.

### Subgraph-Neuindizierung

Weist ein Subgraph fatale Fehler auf und kann sich nicht automatisch erholen, entfernen Sie nicht die aktive Bereitstellung. Rendern und stellen Sie eine neue Version unter dem konfigurierten Mainnet-Graph-Namen bereit:

```bash
# Configure every *_MAINNET singleton and multi-instance list with its deployment block,
# then render, validate and deploy a uniquely labelled fresh version. Graph Node retains
# the prior version while ewpg/ethereum-mainnet indexes the replacement.
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Warten Sie, bis die neue Version den Chain-Head erreicht, und vergleichen Sie erst dann unabhängig ihren Ereignisbereich, bevor Sie eine nachgelagerte Abhängigkeit zulassen. Behalten Sie die vorherige Konfiguration und die Artefakte. Ist ein Rollback erforderlich, stellen Sie die zuvor genehmigte Konfiguration unter einer neuen Versionsbezeichnung erneut bereit; das erzeugt eine neue Version, statt eine der beiden Historien destruktiv zu löschen.

### Solana-Indexer-Wiederherstellung

Hat der Solana-Indexer während eines gRPC-Ausfalls Ereignisse verpasst:

1. Prüfen Sie den zuletzt erfolgreich verarbeiteten Slot in der Indexer-State-Tabelle
2. Der Polling-Fallback des Indexers verarbeitet Slots beim Wiederverbinden automatisch erneut
3. Ist die Lücke zu groß (>10.000 Slots), lösen Sie einen manuellen Backfill aus:

   ```bash
   curl -X POST http://localhost:3001/backfill \
     -H "Content-Type: application/json" \
     -d '{"fromSlot": 285600000, "toSlot": 285614923}'
   ```

## Überwachungsalarme

Konfigurieren Sie Prometheus-Alarmregeln in `monitoring/alerts.yml`:

```yaml
groups:
  - name: indexer
    rules:
      - alert: IndexerLagHigh
        expr: indexer_lag_blocks > 20
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Indexer lag > 20 blocks on {{ $labels.chain }}"

      - alert: IndexerLagCritical
        expr: indexer_lag_blocks > 100
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Indexer lag CRITICAL on {{ $labels.chain }}"

      - alert: GraphNodeDown
        expr: up{job="graph-node"} == 0
        for: 1m
        labels:
          severity: critical
```

## Geplanter Ereignisbereichsvergleich (nicht implementiert)

Registerwerk stellt derzeit keinen Admin-Endpunkt `verify-consistency` bereit. Eine geplante Wiederherstellungskontrolle wird:

1. Den Subgraph nach allen Transfer-Ereignissen im Blockbereich abfragen
2. Dieselben Ereignisse direkt über `eth_getLogs` von der Chain abrufen
3. Beide Mengen vergleichen und etwaige Abweichungen melden

Bis diese Kontrolle implementiert und getestet ist, müssen Betreiber vor einer erneuten Verlassung darauf einen unabhängig kontrollierten Ereignisbereichsvergleich durchführen. Selbst dann würden übereinstimmende Ereignismengen nur Übereinstimmung für den geprüften Bereich belegen — nicht Chain-Finalität, maßgeblichen Registerstatus, rechtliche Wirkung, Abwicklung oder die Identität des bereitgestellten Codes.

# Resilienz und Wiederherstellung

## Fehlermodi und Wiederherstellung

| Komponente | Fehler | Wiederherstellung |
|---|---|---|
| graph-node | Stoppt die Indizierung | Setzt beim Neustart ab `last_indexed_block` fort |
| EVM-RPC-Knoten | Verbindung verloren | `GRAPH_ETHEREUM_REQUEST_RETRIES=10`; Fallback-RPCs konfigurierbar |
| Backend ↔ graph-node | GraphQL nicht erreichbar | `consecutive_errors` wird hochgezählt; setzt bei Wiederverbindung vom Cursor fort |
| Yellowstone gRPC | Stream bricht ab | Backend verbindet neu; Polling-Job füllt Lücken |
| Solana-RPC | Polling schlägt fehl | `indexer_state.status = ERROR`; Monitor alarmiert nach 2 Std. |

## Indexer-Monitor

`IndexerMonitorService` prüft alle 5 Minuten, ob `indexer_state.last_synced_at` älter als 2 Stunden ist. Ist das der Fall, veröffentlicht er ein Audit-Ereignis `INDEXER_STALE`.

## Manuelle Wiederherstellung

Fällt ein Indexer deutlich zurück:

```bash
# Check current cursor
SELECT chain_config_id, indexer_type, last_synced_block, last_synced_at, status
FROM indexer_state;

# Reset cursor to force full re-sync (use with care)
UPDATE indexer_state SET last_synced_block = 0 WHERE chain_config_id = '<uuid>';
```

Starten Sie danach den betreffenden Sync-Dienst oder das Backend neu.

## Deduplizierung

Alle Ereignisse werden mit einer UNIQUE-Einschränkung auf `(chain_config_id, tx_hash, log_index)` gespeichert. Eine erneute Synchronisierung ab einem früheren Block ist sicher — Duplikate werden stillschweigend ignoriert (`ON CONFLICT DO NOTHING`).
