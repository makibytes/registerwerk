---
title: Resilienza e ripresa
---

# Resilienza dell'indicizzatore

Questa pagina descrive come il registro rileva le lacune dell'indicizzatore, rileva e corregge le riorganizzazioni della chain (reorg) e si riprende dalle interruzioni. Queste procedure non stabiliscono correttezza giuridica — vedi `docs/operator/indexers/the-graph.md` per ciò che `synced: true` di un subgraph significa e non significa. Per ciò che accade *a valle* di un reorg rilevato — il giornale degli effetti, la compensazione automatica e il gate di policy che può congelare un asset in attesa di revisione — vedi [Policy di finalità e compensazione dei reorg](finality-and-compensation.md).

!!! note "Corretto rispetto all'implementazione reale"
    Una versione precedente di questa pagina descriveva uno schema `chain_head_block`/`latest_indexed_block`, una scala di livelli di salute `DEGRADED`/`CRITICAL` e un endpoint `POST /backfill` — nulla di tutto ciò esiste in questa codebase. Questa pagina descrive ora ciò che è effettivamente implementato.

!!! note "Da due a tre livelli"
    Questa pagina descriveva in origine un `token_transfer.finality_status` a due livelli: `PROVISIONAL`/`FINAL`/`ORPHANED`. Quella colonna è stata estesa al `finality.api.FinalityLevel` a tre livelli (`PROVISIONAL`/`SAFE`/`FINALIZED`/`ORPHANED`) condiviso con i prodotti fratelli chaincache e chaincheck — `FINAL` è diventato `FINALIZED`, e un nuovo livello `SAFE` si colloca tra PROVISIONAL e FINALIZED per le chain che espongono un checkpoint intermedio (ad es. il tag di blocco `safe` di Ethereum, `ACCEPTED_ON_L2` di Starknet). Ogni esempio di questa pagina è stato aggiornato alla colonna e all'enumerazione attuali.

## Tracciamento dello stato dell'indicizzatore

Il backend mantiene una tabella `indexer_state`, una riga per `(chain_config_id, indexer_type)`:

```sql
SELECT chain_config_id, indexer_type, status,
       last_synced_block, last_final_block, last_synced_at,
       consecutive_errors, last_error
FROM indexer_state
ORDER BY last_synced_at ASC NULLS FIRST;
```

- `last_synced_block` — il cursore di testa/provvisorio: il blocco più alto da cui l'indicizzatore ha letto trasferimenti, che quelle righe siano state o meno finalizzate nel frattempo.
- `last_final_block` — il cursore confermato: il blocco più alto le cui righe `token_transfer` hanno tutte superato la profondità di conferma configurata e sono state verificate rispetto a un hash/stato canonico appena riletto (EVM, Starknet). Sempre `<= last_synced_block`. Null per le chain finali alla scrittura (Solana/Stellar/Canton — vedi sotto), che non hanno mai una finestra non regolata da tracciare.
- `status` — `ACTIVE`, `PAUSED` oppure `ERROR`. Un indicizzatore in `ERROR` con `consecutive_errors >= 10` (5 per Canton) smette di funzionare finché non viene reimpostato manualmente (vedi [Ripristino manuale](#manual-recovery)) — oltre quel punto non esiste autoguarigione automatica.

## Rilevamento e ripristino dei reorg di chain

Ogni chain EVM (tramite graph-node) e ogni chain Starknet riverifica la propria finestra ancora non regolata (PROVISIONAL o SAFE) a ogni ciclo di sincronizzazione, tramite `ReorgGuard`:

- **EVM** — `token_transfer.block_hash` viene registrato per le righe entro la profondità di conferma configurata (`registerwerk.blockchain.tx.confirmations-by-chain`); `ReorgGuard` rilegge l'hash canonico di ciascun blocco tramite `_meta(block: {number})` di graph-node e lo confronta. Un blocco che continua a corrispondere viene promosso di un gradino (PROVISIONAL → SAFE → FINALIZED, con una profondità di conferma con tag `safe` distinta da quella `finalized`); una discrepanza marca come `ORPHANED` ogni riga dal punto di biforcazione (mai eliminata — è un registro regolamentato con obbligo di audit trail) e riavvolge `last_synced_block`/`last_final_block` a `fork_block - 1`, così il ciclo successivo reindicizza l'intervallo interessato. Una chain il cui `ChainConfig.finalitySource` è `CHAINCACHE` (vedi [Integrazione con chaincache](../blockchain/chaincache-integration.md)) ottiene questa riverifica da `ChaincacheFinalityProbe` — una chiamata al `GET /{chain}/api/blocks/{number}/finality` del workload chaincache di quella chain — invece della lettura RPC sopra; qualsiasi errore della sonda (irraggiungibile, 401, 404, 5xx) ripiega sul percorso RPC invece di fabbricare un falso reorg, così una breve indisponibilità di chaincache degrada il tracciamento della finalità al comportamento RPC semplice invece di romperlo. Indipendentemente da quale sonda risponda a questa interrogazione, la chain riceve anche il flusso di eventi durevole in push di chaincache (`ChaincacheDurableStreamManager`) come fonte *aggiuntiva* e senza lacune di osservazioni `BLOCK`/`RETRACTION` che alimentano lo stesso libro `block_finality` descritto sotto — le due sono complementari, non esclusive: il flusso durevole può osservare una ritrattazione prima che questa riverifica basata su polling l'avrebbe rilevata, e il percorso basato su polling continua a funzionare anche se la connessione al flusso durevole è brevemente interrotta.
- **Starknet** — non si usa alcuna primitiva di hash di blocco; la finalità di ogni riga non regolata viene invece ricontrollata tramite il campo `status` di `starknet_getBlockWithTxHashes`: `ACCEPTED_ON_L2` promuove a SAFE, `ACCEPTED_ON_L1` promuove a FINALIZED; un blocco `REJECTED`/`REVERTED` attiva lo stesso percorso di orfanizzazione e riavvolgimento di EVM.
- **Solana** — la finalità è stabilita alla scrittura: i trasferimenti sono indicizzati solo con `commitment: "finalized"`, e il campo `err` della firma viene controllato perché una transazione fallita non venga mai indicizzata come trasferimento riuscito. Non c'è una finestra non regolata separata da riverificare.
- **Stellar / Canton** — la chiusura del ledger (Stellar/Horizon) e il commit del synchronizer (Canton) sono finali una volta osservati; stesso ragionamento di Solana.

Ogni blocco che la finestra non regolata di una chain tocca — quelli effettivamente riverificati sopra — è registrato anche in `block_finality` (una riga per `(chain_config_id, block_number)`, di proprietà del modulo `finality`, alimentata da `ReorgGuard`). È un libro separato da `token_transfer` stesso: è la fonte di verità che `FinalityGate` e il meccanismo di compensazione degli effetti consultano (vedi [Policy di finalità e compensazione dei reorg](finality-and-compensation.md)), così non devono mai scorrere `token_transfer` né importare il modulo indicizzatore. `token_transfer.finality_status` resta una cache denormalizzata dello stesso fatto, comoda per interrogare direttamente i trasferimenti.

`token_transfer.finality_status ∈ {PROVISIONAL, SAFE, FINALIZED, ORPHANED}` può essere interrogato direttamente:

```sql
SELECT chain_config_id, finality_status, count(*)
FROM token_transfer
WHERE finality_status <> 'FINALIZED'
GROUP BY chain_config_id, finality_status;
```

Un conteggio `ORPHANED` diverso da zero è atteso in modo transitorio subito dopo un vero reorg; un conteggio che non diminuisce nei cicli successivi significa che l'intervallo interessato non si reindicizza con successo — controlla `indexer_state.last_error` per quella chain. Un conteggio `SAFE` prolungato (righe che non progrediscono a `FINALIZED`) significa di solito che il modello di finalità della chain si aspetta una profondità di conferma o un tag di blocco che il nodo RPC configurato non riporta — vedi la sezione sul modello di finalità di `docs/operator/blockchain/adding-chains.md`.

**Limite noto:** non esiste una policy di RPC fidato/quorum — la risposta `_meta`/stato di un singolo endpoint RPC configurato è considerata attendibile così com'è per il rilevamento dei reorg. Un nodo RPC difettoso o in ritardo può produrre da sé un falso segnale di reorg; confronta con la configurazione RPC di `docs/operator/blockchain/adding-chains.md` prima di trattare un allarme di reorg come un evento di chain confermato.

## Monitoraggio del ritardo dell'indicizzatore

`IndexerMonitorService` gira ogni 5 minuti e pubblica due gauge Prometheus:

- `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id, indexer_type}` — secondi epoch Unix dell'ultima sincronizzazione riuscita. Genera allarmi come `time() - <metric> > threshold`.
- `registerwerk_indexer_lag_blocks{chain_config_id, indexer_type}` — blocchi tra `last_synced_block` e il più alto `latest_block_number` riportato da un `rpc_node` abilitato e sano di quella chain (riutilizzando i dati di testa già in cache di `RpcNodeHealthService`, non una nuova chiamata RPC). Assente — non zero — per una chain senza nodo sano o senza ancora un blocco sincronizzato, così una serie mancante significa «nessun dato», non «nessun ritardo».

Pubblica inoltre un evento di audit `INDEXER_STALE` ogni volta che un indicizzatore è in `ERROR` o non sincronizza da oltre 2 ore. Le vere regole Prometheus sono in `monitoring/alerts/registerwerk.yml`, gruppo `registerwerk.critical` (`IndexerStaleCritical`/`IndexerStaleWarning`) e gruppo `registerwerk.observability` (`IndexerLagBlocksHigh`, allarme oltre 1000 blocchi per 10 minuti o più) — entrambe sono regole reali e valutate in questo repository, non esempi illustrativi.

## Ripristino manuale { #manual-recovery }

Un indicizzatore che ha raggiunto `consecutive_errors >= 10` (5 per Canton) smette di sincronizzare finché non viene reimpostato.

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

Entrambe le azioni richiedono `REGISTRY_ADMIN` e sono sottoposte ad audit (`INDEXER_RESET`). L'SQL diretto equivalente (ad es. per un percorso scriptato/di emergenza senza l'API) resta:

```sql
SELECT id, chain_config_id, indexer_type, last_synced_block, last_synced_at, status, consecutive_errors, last_error
FROM indexer_state;

UPDATE indexer_state SET status = 'ACTIVE', consecutive_errors = 0, last_error = NULL WHERE id = '<uuid>';
```

## Deduplicazione

`token_transfer` ha vincoli `UNIQUE NULLS NOT DISTINCT` compatibili con il partizionamento su `(chain_config_id, tx_hash, log_index, occurred_at)` per EVM/Starknet e su `(chain_config_id, tx_hash, slot, occurred_at)` per Solana. Risincronizzare da un blocco precedente è sicuro perché ogni servizio di sincronizzazione controlla anche l'identità della transazione prima di inserire, così un intervallo rielaborato viene saltato invece di essere duplicato.

## Modalità di guasto e ripristino

| Componente | Guasto | Ripristino |
|---|---|---|
| graph-node | Smette di indicizzare / riporta `hasIndexingErrors` | Il backend degrada quel ciclo a «scrivere tutto come PROVISIONAL, saltare la riverifica dei reorg» invece di far fallire la sincronizzazione; indaga direttamente su graph-node |
| RPC EVM/Starknet/Stellar | Connessione persa | `consecutive_errors` aumenta; riprende dal cursore alla riconnessione; stato `ERROR` dopo 10 errori consecutivi |
| Solana Yellowstone gRPC | Il flusso cade | Il polling di fallback (`SolanaTransferSyncService`) colma le lacune con un proprio cron di 10 minuti, indipendentemente dallo stato del flusso |
| Flusso del ledger Canton | Il flusso cade | Stato `ERROR` dopo 5 errori consecutivi (soglia più bassa delle altre chain — vedi `CantonTransferSyncService`) |

## Reindicizzazione del subgraph (solo EVM)

Se un subgraph ha errori fatali e non può riprendersi automaticamente, non rimuovere il deployment attivo. Renderizza e distribuisci una nuova versione sotto il nome di grafo mainnet configurato:

```bash
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Attendi che la nuova versione raggiunga la testa della chain, poi confronta in modo indipendente il suo intervallo di eventi prima di consentire che ci si affidi a essa a valle. Conserva la configurazione e gli artefatti precedenti; se serve un rollback, ridistribuisci la configurazione approvata in precedenza sotto una nuova etichetta di versione invece di cancellare in modo distruttivo la cronologia di una delle due versioni.
