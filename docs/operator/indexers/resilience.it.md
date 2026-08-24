---
title: Resilienza e ripresa
---

# Resilienza dell'indicizzatore

Questa pagina descrive come il registro rileva le lacune dell'indicizzatore, si riprende dalle interruzioni e confronta
intervalli di eventi provvisori. Queste procedure non stabiliscono la definitività della catena né la correttezza legale.

## Tracciamento dello stato dell'indicizzatore

Il backend mantiene una tabella `indexer_state` che registra l'ultimo blocco indicizzato correttamente per ciascuna catena:

```sql
SELECT chain_id, network_name, latest_indexed_block,
       chain_head_block,
       (chain_head_block - latest_indexed_block) AS lag_blocks,
       last_updated_at
FROM indexer_state
ORDER BY lag_blocks DESC;
```

Il backend interroga l'API di stato di indicizzazione di graph-node ogni 30 secondi e aggiorna questa tabella.

## Rilevamento gap

Un gap si verifica quando l'indicizzatore resta indietro rispetto alla testa della catena. Il backend classifica il ritardo come segue:

| Ritardo (blocchi) | Stato | Azione |
|-----|--------|--------|
| 0–5 | OK | Funzionamento normale |
| 6–20 | WARN | Avviso registrato, si attiva l'allarme Prometheus |
| 21–100 | DEGRADED | Il dashboard mostra un avviso, viene inviata un'e-mail all'operatore |
| 100+ | CRITICAL | `/actuator/health` restituisce `DOWN`, si attiva l'allarme PagerDuty |

## Procedure di ripristino

### Ripristino di graph-node (EVM)

Se graph-node resta indietro a causa di un'interruzione (downtime) dell'RPC:

1. Controlla la presenza di errori nei log di graph-node:

   ```bash
   docker compose logs --tail=100 graph-node | grep -i "error\|panic"
   ```

2. Verifica che l'endpoint RPC sia raggiungibile:

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

3. Se l'RPC non è raggiungibile, aggiorna `.env` con un RPC di fallback e riavvia:

   ```bash
   docker compose restart graph-node
   ```

4. Monitora l'avanzamento del ripristino su `http://localhost:8030`.

### Reindicizzazione del subgraph

Se un subgraph presenta errori fatali e non può ripristinarsi automaticamente, non rimuovere il deployment attivo.
Genera (render) e distribuisci una nuova versione con il nome del grafo mainnet configurato:

```bash
# Configura ogni singleton e ogni elenco multi-istanza *_MAINNET con il proprio blocco di deployment,
# poi genera (render), valida e distribuisci una nuova versione con un'etichetta univoca. Graph Node conserva
# la versione precedente mentre ewpg/ethereum-mainnet indicizza quella sostitutiva.
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Attendi che la nuova versione raggiunga la testa della catena, quindi confronta il suo intervallo di eventi in modo indipendente
prima di consentirne l'affidamento a valle. Conserva la configurazione e gli artefatti precedenti. Se è necessario un rollback,
ridistribuisci la configurazione precedentemente approvata con una nuova etichetta di versione; questo crea
una nuova versione invece di eliminare in modo distruttivo l'una o l'altra cronologia.

### Ripristino dell'indicizzatore Solana

Se l'indicizzatore Solana ha mancato eventi durante un'interruzione gRPC:

1. Controlla l'ultimo slot elaborato correttamente nella tabella dello stato dell'indicizzatore
2. Il fallback di polling dell'indicizzatore rielabora automaticamente gli slot alla riconnessione
3. Se il gap è troppo ampio (oltre 10.000 slot), avvia un backfill manuale:

   ```bash
   curl -X POST http://localhost:3001/backfill \
     -H "Content-Type: application/json" \
     -d '{"fromSlot": 285600000, "toSlot": 285614923}'
   ```

## Avvisi di monitoraggio

Configura le regole di allarme di Prometheus in `monitoring/alerts.yml`:

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

# Resilienza e ripristino

## Modalità di guasto e ripristino

| Componente | Guasto | Ripristino |
|---|---|---|
| graph-node | Interrompe l'indicizzazione | Al riavvio, riprende da `last_indexed_block` |
| Nodo RPC EVM | Connessione persa | `GRAPH_ETHEREUM_REQUEST_RETRIES=10`; RPC di fallback configurabili |
| Backend ↔ graph-node | GraphQL non raggiungibile | `consecutive_errors` si incrementa; riprende dal cursore alla riconnessione |
| Yellowstone gRPC | Interruzione dello stream | Il backend si riconnette; il job di polling colma le lacune |
| Solana RPC | Il polling fallisce | `indexer_state.status = ERROR`; l'allarme scatta dopo 2 ore |

## Monitor dell'indicizzatore

`IndexerMonitorService` controlla ogni 5 minuti se `indexer_state.last_synced_at` è più vecchio di 2 ore. In tal caso, pubblica un evento di audit `INDEXER_STALE`.

## Ripristino manuale

Se un indicizzatore resta notevolmente indietro:

```bash
# Controlla il cursore corrente
SELECT chain_config_id, indexer_type, last_synced_block, last_synced_at, status
FROM indexer_state;

# Reimposta il cursore per forzare una risincronizzazione completa (usare con cautela)
UPDATE indexer_state SET last_synced_block = 0 WHERE chain_config_id = '<uuid>';
```

Quindi riavvia il servizio di sincronizzazione interessato oppure il backend.

## Deduplicazione

Tutti gli eventi vengono archiviati con un vincolo UNIQUE su `(chain_config_id, tx_hash, log_index)`. Risincronizzare da un blocco precedente è sicuro: i duplicati vengono ignorati silenziosamente (`ON CONFLICT DO NOTHING`).
