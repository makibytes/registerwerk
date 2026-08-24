---
title: Aggiornamento del registro
---

# Aggiornamento del registro { #upgrading-the-registry }

Questa pagina riguarda l'aggiornamento del backend, dei frontend e dei contratti intelligenti. Segui le procedure in ordine: non aggiornare mai i contratti prima di aver aggiornato il backend.

## Aggiornamento del backend { #backend-upgrade }

### 1. Estrai le ultime modifiche { #1-pull-the-latest-changes }

```bash
git fetch origin
git pull origin main
git submodule update --recursive
```

### 2. Esaminare il registro delle modifiche { #2-review-the-changelog }

Esaminare i commit e le modifiche alla configurazione tra il tag attualmente distribuito e il tag di destinazione prima di procedere.

### 3. Creare la nuova immagine backend { #3-build-the-new-backend-image }

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

Oppure esegui il pull dal registro contenitori:

```bash
docker pull ghcr.io/ewpg/registerwerk-backend:latest
```

### 4. Applicare l'aggiornamento { #4-apply-the-upgrade }

```bash
# Stop the backend gracefully (drains in-flight requests)
docker compose stop backend

# Start the new version — Flyway runs migrations automatically on startup
docker compose up -d backend

# Verify health
docker compose logs -f backend | grep -E "Started|ERROR"
curl http://localhost:8080/actuator/health
```

!!! warning
    Le migrazioni del database vengono eseguite automaticamente all'avvio. Se una migrazione fallisce, il backend non verrà avviato. Controlla i log per l'errore di migrazione specifico. Non modificare mai manualmente la tabella della cronologia Flyway.


### 5. Verifica { #5-verify }

Dopo l'avvio:
- Controlla l'API su `http://localhost:8080/swagger-ui.html`
- Crea una chiamata di prova API su un endpoint critico
- Monitora la pista di controllo per eventuali errori imprevisti nei primi 15 minuti

## Aggiornamento frontend { #frontend-upgrade }

```bash
# Operator frontend
cd frontend-operator
npm install
ng build --configuration production
docker compose up -d --build frontend-operator

# Customer frontend
cd ../frontend-customer
npm install
ng build --configuration production
docker compose up -d --build frontend-customer
```

I frontend sono stateless: gli aggiornamenti non richiedono tempi di inattività.

## Aggiornamenti del contratto intelligente { #smart-contract-upgrades }

!!! warning
    Gli aggiornamenti del contratto intelligente sono le operazioni più sensibili. Tutti i contratti passano attraverso una distribuzione e un controllo della rete di test prima di qualsiasi aggiornamento della rete principale. Non aggiornare mai i contratti mainnet senza prima completare la convalida del testnet.


### Contratti aggiornabili e non aggiornabili { #upgradeable-vs-non-upgradeable-contracts }

| Contratto | Aggiornabile | Percorso di aggiornamento |
|----------|------------|-------------|
| `AssetTokenFactory` | No (fabbrica CREATE2) | Distribuisci una nuova factory, aggiorna la configurazione del backend |
| `EwpgTREXFactory` | No | Distribuire la nuova fabbrica |
| `IdentityRegistryStorage` | Sì (proxy UUPS) | Aggiornare l'implementazione del proxy |
| `ModularCompliance` | Sì (proxy UUPS) | Aggiornare l'implementazione del proxy |
| Contratti token (per emissione) | No | Impossibile aggiornare dopo la distribuzione |

### Aggiornamento di un contratto proxy UUPS { #upgrading-a-uups-proxy-contract }

```bash
cd contracts
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow
```

Lo script di aggiornamento:
1. Distribuisce il nuovo contratto di implementazione
2. Chiama `upgradeToAndCall` sul proxy UUPS
3. Verifica che la nuova implementazione sia attiva

### Aggiornamento dei moduli di conformità { #upgrading-compliance-modules }

I moduli di conformità possono essere aggiunti, rimossi o sostituiti senza aggiornare il contratto del token stesso. Questo è il percorso di aggiornamento preferito per le modifiche alla logica di conformità.

```bash
# Add a new compliance module to a token
curl -X POST http://localhost:8080/api/v1/admin/tokens/{tokenAddress}/compliance/modules \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -H "Content-Type: application/json" \
  -d '{"moduleAddress": "0xNewModuleAddress", "chain": "mainnet"}'
```

## Procedura di rollback { #rollback-procedure }

Se un aggiornamento causa problemi, esegui il rollback ripristinando il tag immagine Docker precedente:

```bash
# Backend rollback
docker compose stop backend
docker tag ghcr.io/ewpg/registerwerk-backend:previous \
  registerwerk-backend:latest
docker compose up -d backend
```

Le migrazioni Flyway di questo repository non dispongono di script di rollback automatici. Se una release modifica lo schema, ripristinare il backup precedente all'aggiornamento insieme all'immagine applicativa precedente oppure distribuire una migrazione correttiva revisionata.

## Aggiornamento Kong { #kong-upgrade }

```bash
docker compose stop kong
docker compose pull kong
docker compose up -d kong
```

Dopo aver aggiornato Kong, applica nuovamente la configurazione dichiarativa:

```bash
deck sync --config gateway/kong.yml
```
sidebar_position: 3
---

# Aggiornamenti { #upgrades }

## Aggiornamento backend { #backend-upgrade }

1. Estrai una nuova immagine o crea localmente:
   ```bash
   docker build -t registerwerk-backend:v2.0.0 backend/
   ```

2. Aggiorna il tag immagine `docker-compose.yml`

3. Inizia con il riavvio in sequenza (Flyway esegue la migrazione automatica):
   ```bash
   docker compose up -d --no-deps backend
   ```

4. Verifica l'integrità: `curl http://localhost:8080/actuator/health`

## Aggiornamenti del contratto intelligente { #smart-contract-upgrades }

I moduli di conformità supportano l'aggiornamento sul posto tramite `UpgradeCompliance.s.sol`:

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```

Token e contratti di identità sono **non aggiornabili in base alla progettazione** (l'immutabilità è un requisito legale per i titoli). Gli aggiornamenti richiedono l'implementazione di una nuova suite e la migrazione degli investitori.

## Aggiornamenti del sottografo { #subgraph-upgrades }

Se lo schema del sottografo cambia, preserva la configurazione precedente e distribuisci una nuova versione.
Prima dell'implementazione, verifica che ogni indirizzo singleton abbia il proprio `*_START_BLOCK_<SUFFIX>` effettivo e
che ogni voce BondDesk, AMM e RepoVault utilizzi `address@deploymentBlock`. Un blocco di una fabbrica non è un
sostituto valido per i blocchi di distribuzione delle altre origini.

Renderizza e compila tutti i target configurati senza prima pubblicarli:

```bash
SUBGRAPH_VALIDATE_ONLY=true ./indexer/evm/deploy-subgraph.sh all
```

Quindi distribuisci con un'etichetta di versione che non è mai stata utilizzata per i nomi dei grafici interessati:

```bash
SUBGRAPH_VERSION_LABEL=schema-20260729-01 ./indexer/evm/deploy-subgraph.sh all
```

graph-node reindicizza ciascuna fonte renderizzata dal blocco configurato di quella fonte. Mantieni disponibili le versioni
precedenti e la relativa configurazione per un rollback non distruttivo finché ogni sostituzione
non ha raggiunto la testa della catena e i relativi intervalli di eventi non sono stati riconciliati in modo indipendente. Non rimuovere
il sottografo precedente prima della convalida; rollback significa ridistribuire il manifest
e la configurazione sorgente precedentemente approvata sotto un'altra nuova etichetta di versione.

## Aggiornamenti di Kong { #kong-upgrades }

1. Aggiorna il tag immagine `kong` in `docker-compose.yml` (e `gateway/docker-compose.kong.yml`
se si utilizza lo stack solo gateway autonomo).
2. Riavvia Kong: `docker compose restart kong` — rilegge `gateway/kong.yml` su start
(modalità senza DB, nessuna migrazione da eseguire).

## Aggiornamenti delle dipendenze { #dependency-updates }

- **Java / Spring Boot**: aggiorna `pom.xml`, esegui `mvn verify`
- **Angular**: `ng update @angular/core @angular/cli`
- **Contratti**: `forge update` (aggiorna i sottomoduli git in `contracts/lib/`)
