---
title: Resilienz und Wiederherstellung
---

# Indexer-Resilienz

Diese Seite beschreibt, wie das Register Indexer-Lücken erkennt, Chain-Reorgs erkennt und beheben kann und sich von Ausfällen erholt. Diese Verfahren begründen keine rechtliche Richtigkeit — siehe `docs/operator/indexers/the-graph.md` dazu, was `synced: true` eines Subgraphen bedeutet und was nicht. Was *nach* einem erkannten Reorg geschieht — das Effekt-Journal, die automatische Kompensation und das Policy-Gate, das einen Vermögenswert bis zur Prüfung einfrieren kann — steht unter [Finalitätsrichtlinie und Reorg-Kompensation](finality-and-compensation.md).

!!! note "An der tatsächlichen Implementierung korrigiert"
    Eine frühere Fassung dieser Seite beschrieb ein Schema `chain_head_block`/`latest_indexed_block`, eine Gesundheitsstufen-Leiter `DEGRADED`/`CRITICAL` und einen Endpunkt `POST /backfill` — nichts davon existiert in dieser Codebasis. Diese Seite beschreibt nun, was tatsächlich implementiert ist.

!!! note "Von zwei zu drei Stufen"
    Diese Seite beschrieb ursprünglich ein zweistufiges `token_transfer.finality_status` aus `PROVISIONAL`/`FINAL`/`ORPHANED`. Die Spalte wurde auf das dreistufige `finality.api.FinalityLevel` (`PROVISIONAL`/`SAFE`/`FINALIZED`/`ORPHANED`) erweitert, das mit den Schwesterprodukten chaincache und chaincheck geteilt wird — `FINAL` wurde zu `FINALIZED`, und eine neue Stufe `SAFE` liegt zwischen PROVISIONAL und FINALIZED für Chains, die einen Zwischen-Checkpoint bieten (z. B. das `safe`-Block-Tag von Ethereum, `ACCEPTED_ON_L2` bei Starknet). Jedes Beispiel auf dieser Seite wurde auf die aktuelle Spalte und das aktuelle Enum aktualisiert.

## Indexer-Statusverfolgung

Das Backend führt eine Tabelle `indexer_state`, eine Zeile je `(chain_config_id, indexer_type)`:

```sql
SELECT chain_config_id, indexer_type, status,
       last_synced_block, last_final_block, last_synced_at,
       consecutive_errors, last_error
FROM indexer_state
ORDER BY last_synced_at ASC NULLS FIRST;
```

- `last_synced_block` — der Kopf-/vorläufige Cursor: der höchste Block, aus dem der Indexer überhaupt Transfers gelesen hat, unabhängig davon, ob diese Zeilen inzwischen finalisiert wurden.
- `last_final_block` — der bestätigte Cursor: der höchste Block, dessen `token_transfer`-Zeilen alle die konfigurierte Bestätigungstiefe erreicht haben und gegen einen frisch neu abgerufenen kanonischen Hash/Status geprüft wurden (EVM, Starknet). Immer `<= last_synced_block`. Null bei Chains, die beim Schreiben final sind (Solana/Stellar/Canton — siehe unten), die nie ein ungeklärtes Fenster nachverfolgen müssen.
- `status` — `ACTIVE`, `PAUSED` oder `ERROR`. Ein Indexer in `ERROR` mit `consecutive_errors >= 10` (5 bei Canton) hört auf zu laufen, bis er manuell zurückgesetzt wird (siehe [Manuelle Wiederherstellung](#manual-recovery)) — darüber hinaus gibt es keine automatische Selbstheilung.

## Chain-Reorg-Erkennung und -Wiederherstellung

Jede EVM-Chain (über graph-node) und jede Starknet-Chain prüft ihr noch ungeklärtes (PROVISIONAL- oder SAFE-)Fenster bei jedem Sync-Takt erneut, über `ReorgGuard`:

- **EVM** — `token_transfer.block_hash` wird für Zeilen innerhalb der konfigurierten Bestätigungstiefe (`registerwerk.blockchain.tx.confirmations-by-chain`) festgehalten; `ReorgGuard` ruft den kanonischen Hash jedes solchen Blocks über `_meta(block: {number})` von graph-node neu ab und vergleicht. Ein Block, der weiter übereinstimmt, wird eine Stufe weiter befördert (PROVISIONAL → SAFE → FINALIZED, mit einer `safe`-getaggten Bestätigungstiefe, die sich von der `finalized`-Tiefe unterscheidet); ein Abweichen markiert jede Zeile ab dem Fork-Punkt als `ORPHANED` (niemals gelöscht — dies ist ein reguliertes Register mit Audit-Trail-Pflicht) und setzt `last_synced_block`/`last_final_block` auf `fork_block - 1` zurück, sodass der nächste Takt den betroffenen Bereich neu indiziert. Eine Chain, deren `ChainConfig.finalitySource` `CHAINCACHE` ist (siehe [chaincache-Integration](../blockchain/chaincache-integration.md)), erhält diese Neuprüfung von `ChaincacheFinalityProbe` — ein Aufruf des chaineigenen `GET /{chain}/api/blocks/{number}/finality` der chaincache-Workload — statt des obigen RPC-Abrufs; jeder Fehler der Probe (nicht erreichbar, 401, 404, 5xx) fällt auf den RPC-Pfad zurück, statt einen falschen Reorg zu erzeugen, sodass ein kurzzeitiger chaincache-Ausfall die Finalitätsverfolgung auf das reine RPC-Verhalten zurückstuft, statt sie zu brechen. Unabhängig davon, welche Probe diese Abfrage beantwortet, erhält die Chain außerdem den Push-basierten, dauerhaften Ereignisstrom von chaincache (`ChaincacheDurableStreamManager`) als *zusätzliche*, lückenlose Quelle von `BLOCK`/`RETRACTION`-Beobachtungen für dasselbe unten beschriebene `block_finality`-Hauptbuch — beide ergänzen sich, schließen sich nicht aus: Der dauerhafte Strom kann einen Widerruf beobachten, bevor ihn diese abfragebasierte Neuprüfung erfasst hätte, und der abfragebasierte Pfad funktioniert weiter, auch wenn die Verbindung zum dauerhaften Strom kurz unterbrochen ist.
- **Starknet** — es wird kein Block-Hash-Primitiv verwendet; stattdessen wird die Finalität jeder ungeklärten Zeile über das Feld `status` von `starknet_getBlockWithTxHashes` neu geprüft: `ACCEPTED_ON_L2` befördert zu SAFE, `ACCEPTED_ON_L1` zu FINALIZED; ein Block mit `REJECTED`/`REVERTED` löst denselben Orphan-und-Rücksetz-Pfad wie bei EVM aus.
- **Solana** — die Finalität wird beim Schreiben hergestellt: Transfers werden nur mit `commitment: "finalized"` indiziert, und das Feld `err` der Signatur wird geprüft, damit eine fehlgeschlagene Transaktion nie als erfolgreicher Transfer indiziert wird. Es gibt kein separates ungeklärtes Fenster, das neu zu prüfen wäre.
- **Stellar / Canton** — Ledger-Abschluss (Stellar/Horizon) und Synchronizer-Commit (Canton) sind final, sobald sie beobachtet werden; dieselbe Überlegung wie bei Solana.

Jeder Block, den das ungeklärte Fenster einer Chain je berührt — die oben tatsächlich neu geprüften — wird außerdem in `block_finality` festgehalten (eine Zeile je `(chain_config_id, block_number)`, im Besitz des Moduls `finality`, von `ReorgGuard` gespeist). Das ist ein von `token_transfer` getrenntes Hauptbuch: Es ist die Quelle der Wahrheit, die `FinalityGate` und die Effekt-Kompensation befragen (siehe [Finalitätsrichtlinie und Reorg-Kompensation](finality-and-compensation.md)), sodass sie nie `token_transfer` durchsuchen oder das Indexer-Modul importieren müssen. `token_transfer.finality_status` bleibt ein denormalisierter Cache derselben Tatsache, praktisch zum direkten Abfragen von Transfers.

`token_transfer.finality_status ∈ {PROVISIONAL, SAFE, FINALIZED, ORPHANED}` lässt sich direkt abfragen:

```sql
SELECT chain_config_id, finality_status, count(*)
FROM token_transfer
WHERE finality_status <> 'FINALIZED'
GROUP BY chain_config_id, finality_status;
```

Eine von null verschiedene `ORPHANED`-Zahl ist unmittelbar nach einem echten Reorg vorübergehend zu erwarten; schrumpft sie in den folgenden Takten nicht, wird der betroffene Bereich nicht erfolgreich neu indiziert — prüfen Sie `indexer_state.last_error` für diese Chain. Eine anhaltende `SAFE`-Zahl (Zeilen, die nicht zu `FINALIZED` fortschreiten) bedeutet meist, dass das Finalitätsmodell der Chain eine Bestätigungstiefe oder ein Block-Tag erwartet, das der konfigurierte RPC-Knoten nicht meldet — siehe den Abschnitt zum Finalitätsmodell in `docs/operator/blockchain/adding-chains.md`.

**Bekannte Einschränkung:** Es gibt keine Richtlinie für vertrauenswürdige RPCs/Quorum — die `_meta`-/Status-Antwort eines einzelnen konfigurierten RPC-Endpunkts wird für die Reorg-Erkennung unbesehen vertraut. Ein fehlerhafter oder nachhinkender RPC-Knoten kann selbst ein falsches Reorg-Signal erzeugen; gleichen Sie mit der RPC-Konfiguration in `docs/operator/blockchain/adding-chains.md` ab, bevor Sie einen Reorg-Alarm als bestätigtes Chain-Ereignis behandeln.

## Überwachung der Indexer-Verzögerung

`IndexerMonitorService` läuft alle 5 Minuten und veröffentlicht zwei Prometheus-Gauges:

- `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id, indexer_type}` — Unix-Epoche in Sekunden der letzten erfolgreichen Synchronisation. Alarmieren Sie als `time() - <metric> > threshold`.
- `registerwerk_indexer_lag_blocks{chain_config_id, indexer_type}` — Blöcke zwischen `last_synced_block` und dem höchsten `latest_block_number`, den ein aktivierter und gesunder `rpc_node` dieser Chain meldet (die bereits zwischengespeicherten Kopfdaten des `RpcNodeHealthService` werden wiederverwendet, kein neuer RPC-Aufruf). Fehlt — nicht null — bei einer Chain ohne gesunden Knoten oder ohne synchronisierten Block, sodass eine fehlende Reihe „keine Daten" bedeutet, nicht „keine Verzögerung".

Außerdem veröffentlicht er ein Audit-Ereignis `INDEXER_STALE`, sobald ein Indexer `ERROR` ist oder seit über 2 Stunden nicht synchronisiert hat. Die tatsächlichen Prometheus-Regeln stehen in `monitoring/alerts/registerwerk.yml`, Gruppe `registerwerk.critical` (`IndexerStaleCritical`/`IndexerStaleWarning`) und Gruppe `registerwerk.observability` (`IndexerLagBlocksHigh`, Alarm bei >1000 Blöcken über mindestens 10 Minuten) — beides sind echte, ausgewertete Regeln in diesem Repository, keine Beispiele.

## Manuelle Wiederherstellung { #manual-recovery }

Ein Indexer, der `consecutive_errors >= 10` erreicht hat (5 bei Canton), hört auf zu synchronisieren, bis er zurückgesetzt wird.

```bash
# List every indexer's current state
curl -H "Authorization: Bearer $OPERATOR_JWT" http://localhost:48080/api/v1/indexers

# Clear the error state — the next scheduled tick resumes from the existing cursor, no restart required
curl -X POST -H "Authorization: Bearer $OPERATOR_JWT" \
  "http://localhost:48080/api/v1/indexers/<indexer-state-id>/reset"

# Force a full re-sync from genesis instead (only if the existing cursor itself is untrustworthy —
# e.g. after a manual chain-state correction; this re-processes the chain's entire history)
curl -X POST -H "Authorization: Bearer $OPERATOR_JWT" \
  "http://localhost:48080/api/v1/indexers/<indexer-state-id>/reset?fullResync=true"
```

Beide Aktionen erfordern `REGISTRY_ADMIN` und werden protokolliert (`INDEXER_RESET`). Das gleichwertige direkte SQL (z. B. für einen Skript-/Notfallpfad ohne die API) bleibt:

```sql
SELECT id, chain_config_id, indexer_type, last_synced_block, last_synced_at, status, consecutive_errors, last_error
FROM indexer_state;

UPDATE indexer_state SET status = 'ACTIVE', consecutive_errors = 0, last_error = NULL WHERE id = '<uuid>';
```

## Deduplizierung

`token_transfer` hat partitionskompatible `UNIQUE NULLS NOT DISTINCT`-Constraints auf `(chain_config_id, tx_hash, log_index, occurred_at)` für EVM/Starknet und `(chain_config_id, tx_hash, slot, occurred_at)` für Solana. Eine erneute Synchronisation ab einem früheren Block ist sicher, weil jeder Sync-Dienst vor dem Einfügen zusätzlich die Transaktionsidentität prüft, sodass ein erneut verarbeiteter Bereich übersprungen statt dupliziert wird.

## Fehlermodi und Wiederherstellung

| Komponente | Fehler | Wiederherstellung |
|---|---|---|
| graph-node | Hört auf zu indizieren / meldet `hasIndexingErrors` | Das Backend stuft diesen Takt auf „alles als PROVISIONAL schreiben, Reorg-Neuprüfung überspringen" zurück, statt die Synchronisation scheitern zu lassen; untersuchen Sie graph-node direkt |
| EVM-/Starknet-/Stellar-RPC | Verbindung verloren | `consecutive_errors` steigt; setzt beim Wiederverbinden am Cursor fort; Status `ERROR` nach 10 aufeinanderfolgenden Fehlern |
| Solana Yellowstone gRPC | Strom bricht ab | Der Polling-Fallback (`SolanaTransferSyncService`) schließt Lücken mit eigenem 10-Minuten-Cron, unabhängig vom Zustand des Stroms |
| Canton-Ledger-Strom | Strom bricht ab | Status `ERROR` nach 5 aufeinanderfolgenden Fehlern (niedrigere Schwelle als bei anderen Chains — siehe `CantonTransferSyncService`) |

## Subgraph-Neuindizierung (nur EVM)

Hat ein Subgraph fatale Fehler und kann sich nicht automatisch erholen, entfernen Sie das aktive Deployment nicht. Rendern und bringen Sie eine frische Version unter dem konfigurierten Mainnet-Graph-Namen aus:

```bash
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Warten Sie, bis die neue Version den Chain-Kopf erreicht hat, und vergleichen Sie ihren Ereignisbereich unabhängig, bevor nachgelagert darauf vertraut wird. Bewahren Sie die bisherige Konfiguration und die Artefakte auf; ist ein Rollback nötig, bringen Sie die zuvor freigegebene Konfiguration unter einem neuen Versionslabel erneut aus, statt die Historie einer der Versionen destruktiv zu löschen.
