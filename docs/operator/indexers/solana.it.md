---
title: Solana (Yellowstone gRPC)
---

# Indicizzatore Solana — Yellowstone gRPC

L'indicizzatore Solana utilizza **Yellowstone**, un plug-in Geyser che trasmette aggiornamenti e transazioni dell'account in tempo reale tramite gRPC. Un fallback di polling copre i periodi in cui il flusso gRPC non è disponibile.

## Architettura

```
Solana Validator (with Yellowstone Geyser plugin)
        |
        | (gRPC stream)
        v
  indexer/solana (Node.js service)
        |
        | (REST / WebSocket)
        v
  Backend Spring Boot
```

## Prerequisiti

- Un endpoint Solana RPC con supporto **Yellowstone gRPC**
  - Opzioni ospitate: [Helius](https://helius.dev), [Triton](https://triton.one)
  - Self-hosted: richiede un validatore Solana con il plugin Yellowstone installato

## Configurazione

`indexer/solana/yellowstone.yaml`:

```yaml
endpoint: "${SOLANA_YELLOWSTONE_ENDPOINT}"
x_token: "${SOLANA_YELLOWSTONE_TOKEN}"

subscriptions:
  - type: transaction
    accountInclude:
      - "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"  # SPL Token program
    commitment: confirmed

polling:
  enabled: true
  fallback_rpc: "${SOLANA_MAINNET_RPC_URL}"
  poll_interval_seconds: 2
  max_blocks_per_poll: 10
```

Da impostare in `.env`:

```bash
SOLANA_YELLOWSTONE_ENDPOINT=https://your-yellowstone-endpoint.helius.xyz:2083
SOLANA_YELLOWSTONE_TOKEN=your_api_token
SOLANA_MAINNET_RPC_URL=https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
SOLANA_DEVNET_RPC_URL=https://devnet.helius-rpc.com/?api-key=YOUR_KEY
```

## Avvio dell'indicizzatore Solana

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Oppure per lo sviluppo locale:

```bash
cd indexer/solana && npm install
npm start
```

## Tracciamento dei token SPL

L'indicizzatore Solana tiene traccia degli eventi del programma token SPL:
- `Transfer` — trasferimenti di token tra account
- `MintTo` — conio di token
- `Burn` — distruzione di token

Filtra gli eventi limitandosi a quelli che coinvolgono indirizzi mint di token registrati nel backend. L'attività SPL non correlata viene ignorata.

## Fallback di polling

Se il flusso gRPC di Yellowstone si disconnette o diventa non disponibile, l'indicizzatore passa automaticamente alla modalità polling:

1. Esegue il polling di `getSignaturesForAddress` per il programma token SPL all'intervallo configurato
2. Recupera i dettagli completi della transazione per ogni nuova firma
3. Analizza gli eventi token dai log delle transazioni
4. Torna a gRPC non appena il flusso si riconnette

Il fallback di polling è meno efficiente ma garantisce che nessun evento venga perso durante le interruzioni del provider.

## Monitoraggio

L'indicizzatore Solana espone un endpoint di stato:

```bash
curl http://localhost:3001/health
```

Risposta:

```json
{
  "status": "healthy",
  "mode": "grpc",
  "latestSlot": 285614923,
  "lastEventAt": "2025-04-06T12:00:00Z"
}
```

Se `mode` mostra `polling`, il flusso gRPC è inattivo e il fallback è attivo. Verifica l'endpoint Yellowstone.

## Aggiungere Solana Devnet

L'indicizzatore devnet viene eseguito come istanza separata:

```bash
SOLANA_NETWORK=devnet \
  SOLANA_YELLOWSTONE_ENDPOINT=https://devnet-yellowstone.example.com:2083 \
  SOLANA_YELLOWSTONE_TOKEN=your_devnet_token \
  SOLANA_DEVNET_RPC_URL=https://api.devnet.solana.com \
  npm start
```

Oppure aggiungi un secondo servizio nel file Docker Compose con `SOLANA_NETWORK=devnet`.

# Solana — Indicizzatore Yellowstone gRPC

I trasferimenti Solana sono monitorati tramite [Yellowstone Dragon's Mouth](https://github.com/rpcpool/yellowstone-grpc), un proxy gRPC per la Geyser Plugin Interface di Solana.

## Avvio di Yellowstone

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Imposta `YELLOWSTONE_UPSTREAM_ENDPOINT` su un endpoint abilitato per Geyser:
- [Helius](https://helius.dev)
- [Triton One](https://triton.one)
- Un validatore Solana self-hosted con il plugin Yellowstone

## Come funziona

`SolanaTransferSyncService` nel backend:
1. Apre una sottoscrizione gRPC a Yellowstone all'avvio (`@PostConstruct`)
2. Filtra le transazioni che coinvolgono un indirizzo mint SPL noto
3. Analizza i trasferimenti ed esegue l'upsert in `token_transfer`

Se il flusso gRPC si interrompe, il servizio si riconnette automaticamente.

## Fallback di polling

Un job `@Scheduled` separato viene eseguito ogni 10 minuti:
- Chiama `getSignaturesForAddress` per ogni mint SPL noto
- Colma le eventuali lacune causate dai periodi di inattività dello stream
- La deduplicazione tramite vincolo UNIQUE impedisce il doppio conteggio

## Registrazione di un nuovo token SPL

Quando viene creato un deployment di asset Solana tramite l'API, il backend inizia automaticamente a monitorare il relativo indirizzo mint. Non è necessaria alcuna configurazione manuale.
