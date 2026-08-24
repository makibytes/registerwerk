---
title: The Graph (indicizzatore EVM)
---

# The Graph — indicizzazione EVM

Registerwerk utilizza `graph-node` per creare proiezioni provvisorie derivate dagli eventi per i contratti EVM
configurati. Le entità del subgraph non sono attestazioni di definitività della catena, voci di registro legale,
prova del regolamento legale, né prova dell'identità del codice distribuito. Riconcilia la catena configurata,
le conferme, il deployment del contratto e il registro legale autorevole prima di affidarti a esse.

## Installazione e verifica

```bash
cd indexer/evm/subgraph
npm install
npm test
```

`npm test` verifica la parità ABI/evento rispetto agli artefatti Forge già archiviati, testa il rendering del manifest,
esegue la generazione del codice di Graph e compila ogni mapping.

## Configurazione di deployment richiesta

La destinazione di deployment seleziona un suffisso di ambiente:

| Destinazione | Rete The Graph | Suffisso |
|---|---|---|
| `mainnet` | `mainnet` | `MAINNET` |
| `sepolia` | `sepolia` | `SEPOLIA` |
| `polygon` | `polygon` | `POLYGON` |
| `polygon-amoy` | `polygon-amoy` | `POLYGON_AMOY` |
| `base` | `base` | `BASE` |
| `base-sepolia` | `base-sepolia` | `BASE_SEPOLIA` |
| `arbitrum-one` | `arbitrum-one` | `ARBITRUM` |
| `arbitrum-sepolia` | `arbitrum-sepolia` | `ARBITRUM_SEPOLIA` |
| `avalanche` | `avalanche` | `AVALANCHE` |
| `avalanche-fuji` | `avalanche-fuji` | `AVALANCHE_FUJI` |
| `optimism` | `optimism` | `OPTIMISM` |
| `optimism-sepolia` | `optimism-sepolia` | `OPTIMISM_SEPOLIA` |

Per ciascun suffisso, configura le quattro fonti singleton di seguito. Il loro blocco iniziale è impostato su zero
per impostazione predefinita, ma gli operatori dovrebbero sempre usare il blocco di deployment effettivo per rendere
esplicito l'ambito della riproduzione. Ogni fonte ha una provenienza indipendente: non copiare il blocco di una
factory negli altri campi sorgente a meno che le ricevute di deployment non dimostrino effettivamente che condividono
quel blocco.

```dotenv
ASSET_TOKEN_FACTORY_ADDRESS_SEPOLIA=0x...
ASSET_TOKEN_FACTORY_START_BLOCK_SEPOLIA=120
REPO_MARKET_FACTORY_ADDRESS_SEPOLIA=0x...
REPO_MARKET_FACTORY_START_BLOCK_SEPOLIA=130
DVP_SETTLEMENT_ADDRESS_SEPOLIA=0x...
DVP_SETTLEMENT_START_BLOCK_SEPOLIA=140
CONFIDENTIAL_FACTORY_ADDRESS_SEPOLIA=0x...
CONFIDENTIAL_FACTORY_START_BLOCK_SEPOLIA=150
```

I deployment di BondDesk, Stablecoin AMM e RepoVault non sono rilevabili in modo affidabile tramite factory. Elenca
ogni istanza esplicitamente come `address@deploymentBlock`, separata da virgole:

```dotenv
BOND_DESK_INSTANCES_SEPOLIA=0xDesk1@123,0xDesk2@456
STABLECOIN_AMM_INSTANCES_SEPOLIA=0xAmm1@123,0xAmm2@456
REPO_VAULT_INSTANCES_SEPOLIA=0xVault1@123,0xVault2@456
```

Se l'operatore configura zero istanze per un ruolo, deve impostare il relativo elenco esattamente su `NONE`. Questa è
un'asserzione dell'operatore sulla configurazione, non la prova che non esista alcun deployment on-chain. Un elenco
non impostato o vuoto genera un rifiuto (fail closed). Il renderer rifiuta inoltre indirizzi zero, blocchi con formato
errato e un indirizzo riutilizzato da qualsiasi altra fonte statica.

## Deploy

```bash
SUBGRAPH_VERSION_LABEL=sepolia-20260729-01 ./indexer/evm/deploy-subgraph.sh sepolia
```

Usa `SUBGRAPH_VALIDATE_ONLY=true` per eseguire il rendering, la generazione e la compilazione senza inviare un
deployment a graph-node. `all` elabora ogni destinazione della tabella e richiede quindi la configurazione per ogni
suffisso. Un deployment reale richiede anche `SUBGRAPH_VERSION_LABEL`; scegli una nuova etichetta per ogni deployment
su quel nome di grafo. Il wrapper rifiuta un'etichetta assente; l'operatore deve assicurarsi che l'etichetta fornita
sia nuova. Mantieni disponibile la versione precedente finché quella sostitutiva non ha raggiunto la testa della
catena e superato la riconciliazione indipendente dell'intervallo di eventi.

AssetTokenFactory crea fonti dati token dinamiche da `TokenDeployed` e `VaultDeployed`. RepoMarketFactory crea allo
stesso modo fonti RepoMarket da `MarketCreated`. Le nuove istanze dei tre tipi di contratto elencati esplicitamente
richiedono un aggiornamento dell'elenco e una nuova distribuzione del subgraph. Gli indirizzi, gli ID asset, i
riferimenti ai token, i parametri oracle e i blocchi di osservazione emessi dalla factory vengono archiviati come
attestazioni di eventi. Non verificano il bytecode distribuito, la provenienza del deployment o il collegamento a un
record del database dell'applicazione.

## Migrazione e riproduzione della proiezione

Le entità ERC-3525 relative al valore nozionale owner/slot e le entità del ciclo di vita delle richieste ERC-7540
richiedono l'ordine degli eventi a partire dal deployment del contratto. Le righe `HolderBalance` esistenti per
ERC-3525 hanno conteggiato gli ID token e non possono essere convertite in valore nozionale. Non copiarle in
`Erc3525OwnerSlotBalance`.

Per questa revisione dello schema, distribuisci una nuova versione del subgraph e riproduci ogni fonte a partire dal
suo reale blocco di deployment. Una proiezione `INCOMPLETE` non può ricostruire proprietari, slot, valori, tipi di
richiesta mancanti, né la configurazione precedente del mercato RepoVault. Ogni proiezione RepoVault resta
`INCOMPLETE` a meno che la provenienza del deployment e la riproduzione completa non siano dimostrate al di fuori di
questo subgraph; il semplice osservare il primo evento a un indirizzo statico configurato non fornisce tale prova.
Mantieni la vecchia distribuzione disponibile per il rollback finché la nuova proiezione non ha raggiunto la testa
della catena ed è stata riconciliata in modo indipendente.

Gli importi `Allocated` e `Deallocated` di RepoVault sono proiettati solo come flusso di cassa netto con segno. La
deallocazione può superare l'allocazione precedente per effetto di interessi o realizzo di perdite, quindi questo
valore non rappresenta il capitale in essere, una posizione di mercato scalata o il NAV, e un totale negativo non
costituisce di per sé un'incoerenza.

## Monitoraggio e interrogazione

```bash
curl -s http://localhost:8030/graphql \
  -d '{"query":"{indexingStatuses{subgraph synced health chains{network latestBlock{number}}}}"}' \
  | jq '.data.indexingStatuses[]'
```

`synced: true` descrive solo l'avanzamento di graph-node; non è un segnale di definitività né di efficacia legale.
Interroga un deployment su `http://localhost:8000/subgraphs/name/<subgraph-name>`.

Gli errori comuni sono la limitazione (throttling) dell'RPC, memoria insufficiente, artefatti Forge obsoleti, deriva
dell'ABI o una configurazione della fonte statica mancante. Esegui `npm test` prima di diagnosticare un errore di
deployment.
