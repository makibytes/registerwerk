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
curl -X POST http://localhost:48000/api/v1/admin/chains \
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
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
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

## Governance e fiducia dei nodi RPC

Il nodo RPC ascoltato dal registro determina quale stato della catena esso ritiene vero; i nodi sono quindi governati come chiavi:

- **Autenticazione rafforzata e secondo approvatore.** Aggiungere, modificare, abilitare, disabilitare, fissare (`exclusive`) ed eliminare un nodo (`/api/v1/admin/chains/{chainId}/nodes...`), nonché reimpostare il pin del genesis, richiedono un token di autenticazione rafforzata e un secondo approvatore (`X-Dual-Control-Token`, motivo `RPC_NODE_CHANGE`). L'evento di audit `RPC_NODE_*` registra l'operatore, il secondo approvatore e l'URL precedente e nuovo; i segreti negli URL (userinfo, valori di query, segmenti di percorso simili a una chiave) vengono oscurati.
- **Solo https.** Gli URL dei nodi devono usare `https`/`wss`. `http`/`ws` in chiaro è ammesso solo per host loopback o privati e solo con `registerwerk.rpc.allow-insecure-private=true` (ambienti demo). `chain_config.rpc_allowed_hosts` (separato da virgole, è possibile `*.example.org`) limita facoltativamente gli host di una catena. Le regole valgono all'aggiunta e al cambio di URL.
- **Identità della catena.** All'aggiunta e a ogni ciclo di salute, `eth_chainId` deve coincidere con `chain_config.chain_id` fissato e l'hash del blocco genesis con `chain_config.genesis_hash` (Solana: `getGenesisHash`). Il primo nodo corrispondente fissa l'hash del genesis. Un nodo difforme viene rifiutato all'aggiunta; in seguito è contrassegnato come non sano con il motivo `CHAIN_MISMATCH` e non viene mai instradato, nemmeno come ultima risorsa. Dopo un reset legittimo di una devnet, il pin si cancella con `POST /api/v1/admin/chains/{chainId}/nodes/genesis-pin/reset` (autenticazione rafforzata e secondo approvatore); il ciclo successivo fissa il nuovo genesis.
- **Il miglior blocco è una mediana.** Il ritardo si misura rispetto all'altezza mediana dei candidati al routing (la più bassa tra due), non rispetto al massimo. Un nodo avanti di oltre `registerwerk.rpc.max-plausible-jump-blocks` (predefinito 1000) rispetto a tale riferimento è messo in quarantena (`IMPLAUSIBLE_HEIGHT`).
- **Failover rapido.** Un nodo diventa non sano alla prima sonda fallita e richiede due sonde riuscite consecutive per rientrare (`health_reason` `RECOVERING`). Con due o più nodi instradabili, le letture con errore di trasporto vengono ripetute sul nodo successivo nella stessa chiamata (al massimo tre tentativi); il nodo in errore resta ultimo per 30 secondi. Le transazioni sono inviate a un solo nodo. Dopo che un nodo ha accettato una trasmissione, le letture preferiscono quel nodo per due minuti (lettura delle proprie scritture). Le letture lending a più chiamate (quotazioni, aggiornamento posizioni) girano su un unico numero di blocco, e un health factor lending è affidabile solo se il nodo usato è al massimo un blocco indietro.
- **Conferma da una seconda fonte.** Prima di completare una transazione che modifica il registro, almeno due nodi sani devono concordare con la ricevuta: la stessa transazione (hash di blocco, contratto destinatario e mittente tramite `eth_getTransactionByHash`) e la stessa ricevuta tramite `eth_getTransactionReceipt` (stato, numero e hash di blocco, gas usato ed elenco completo dei log). Quali funzioni modificano il registro è un elenco esplicito di nomi di funzione nel backend (trasferimenti forzati, burn, mint, blocchi incluso il `setAddressFrozen` disposto da un tribunale, pausa, claim, registro delle identità, moduli di compliance e relativi limiti come `setMaxBalance`, NAV `setNavPerShare`, proprietà, ruoli, ecosistema e registro dApp); una funzione non classificata è trattata come modificatrice del registro. `registerwerk.blockchain.tx.second-source-methods` aggiunge solo nomi di funzione esatti (o `*`). Una discordanza trattiene la transazione (`registerwerk.confirmation.second_source_mismatch`, log ERROR). Con meno di due nodi sani la transazione prosegue fuori dalla modalità di produzione e `registerwerk.confirmation.single_source` la conta; **in modalità di produzione (`REGISTERWERK_PRODUCTION_MODE=true`) viene trattenuta** come `PENDING` (`registerwerk.confirmation.single_source_held`, alert `RpcSingleSourceHeld`, poi `RpcSecondSourceHoldOverdue`) finché un secondo nodo indipendente non è sano. Le commissioni del nodo principale vengono controllate con un secondo nodo: commissione = `min(A, B x 1,5)`.
- **I parametri di finalità** provengono solo da `chain_config` e `registerwerk.blockchain.tx.*`. Un nodo chaincache resta fonte per gli hash dei blocchi, ma il suo livello SAFE/FINALIZED autodichiarato è limitato dalla profondità o dai tag configurati. Quale livello di finalità basti giuridicamente per un'iscrizione secondo il modello di catena resta una decisione aperta (T4-08).
- **Il chain id deve essere impostato.** La firma rifiuta ogni transazione su una chain EVM il cui `chain_config.chain_id` è vuoto (`chain id unpinned`). All'avvio il backend registra un ERROR con le chain EVM abilitate che hanno deployment CONFIRMED ma nessun chain id ed espone la metrica `registerwerk_evm_chains_unpinned` (allarme `EvmChainIdUnpinned`). Volutamente non esiste un riempimento automatico: imposti esplicitamente l'id della rete (`PATCH /api/v1/admin/chains/{id}` con `chainId`) prima della prossima transazione.
