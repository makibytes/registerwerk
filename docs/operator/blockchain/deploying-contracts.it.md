---
title: Distribuzione di contratti
---

# Distribuzione di contratti intelligenti { #deploying-smart-contracts }

## Panoramica { #overview }

Tutti i contratti risiedono in `contracts/` e sono compilati con [Foundry](https://book.getfoundry.sh/).

### Architettura del contratto { #contract-architecture }

```
AssetTokenFactory (CREATE2 factory)
├── EwpgERC20       — Fungible security token
├── EwpgERC721      — Non-fungible security token
├── EwpgERC1155     — Multi-token (e.g. bond tranches)
└── EwpgERC3643     — Regulated security token (T-REX / ERC-3643)
    └── EwpgTREXFactory — T-REX suite deployer
        ├── Token
        ├── IdentityRegistry
        ├── IdentityRegistryStorage
        ├── ModularCompliance
        ├── ClaimTopicsRegistry
        └── TrustedIssuersRegistry

ConfidentialERC3643 — Encrypted balances on Fhenix / Inco
```

## Costruisci { #build }

```bash
cd contracts
forge build
```

Gli artefatti compilati arrivano in `contracts/out/`. Maven `web3j-maven-plugin` li legge per generare wrapper Java.

## Test { #test }

```bash
forge test -vvv
forge coverage
```

Destinazione: copertura della linea ≥80%.

## Distribuisci su testnet { #deploy-to-testnet }

```bash
export ETH_SEPOLIA_RPC=https://rpc.sepolia.org
export DEPLOYER_PRIVATE_KEY=0x<key>

forge script script/DeployTestnet.s.sol \
  --rpc-url $ETH_SEPOLIA_RPC \
  --broadcast \
  --verify
```

## Distribuisci su mainnet { #deploy-to-mainnet }

```bash
forge script script/Deploy.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow   # 1 tx per block for safety
```

## Determinismo di CREATE2 { #create2-determinism }

`AssetTokenFactory` utilizza `CREATE2` con salt `keccak256(abi.encode(assetId, tokenStandard))`. Ciò significa:
- Il backend può precalcolare l'indirizzo del contratto **prima** che la transazione venga estratta
- L'indirizzo viene memorizzato come `PENDING` in `asset_deployment` immediatamente
- La distribuzione è idempotente: la riesecuzione della stessa distribuzione produrrà lo stesso indirizzo

Poiché salt e initcode derivano da calldata pubblici, nelle factory distribuite prima che
`deployToken`/`deployVault` fossero riservate al registro chiunque può riprodurre per primo i
calldata del registro e far fallire la transazione del registro stesso (`CREATE2 failed`). Il
backend verifica quindi `predictAddress` prima dell'invio e di nuovo dopo un revert: se a
quell'indirizzo esiste già un contratto e i suoi `assetId()` e `registry()` corrispondono, adotta
quel contratto e la relativa transazione di creazione invece di fallire. Se il contratto non è
nostro (ad esempio perché l'autorità del registro è stata trasferita), il deployment fallisce con
un errore che indica l'indirizzo.

!!! note "Introduzione della factory riservata al registro"
    `registryWallet` è immutabile e il `bindFactory` di ogni deployer può essere chiamato una sola
    volta, quindi la restrizione vale solo per una nuova generazione di factory. Per ogni chain:
    distribuire i sei deployer e una nuova `AssetTokenFactory` (`script/Deploy.s.sol` /
    `DeployL2.s.sol` / `DeployTestnet.s.sol`), quindi aggiornare
    `registerwerk.contracts.asset-token-factory.<chain>` e riavviare il backend. I token esistenti
    mantengono i loro indirizzi (salvati in `asset_deployment`); le vecchie factory ancora attive
    restano protette dal controllo di adozione del backend descritto sopra.

## Aggiornamento dei moduli di conformità { #upgrading-compliance-modules }

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```

## Da una singola chiave di deployment a multisig/timelock

!!! warning "Nessuno script di questo repository lo fa per te"
    Ogni file `script/Deploy*.s.sol` firma con l'unico EOA dietro
    `REGISTRY_WALLET_PRIVATE_KEY` e concede a quello stesso indirizzo `DEFAULT_ADMIN_ROLE` +
    `OPERATOR_ROLE` su `OrgRegistry`, `PermissionRegistry`, `EcosystemTrustedIssuersRegistry`,
    `PermissionOracle` e `DappRegistry`, oltre alla proprietà `Ownable` di ogni token generato da
    `AssetTokenFactory` — in modo permanente, **senza alcun passaggio di trasferimento**.
    `UpgradeCompliance.s.sol` di `EwpgBondDesk` è l'unica eccezione: una variabile d'ambiente facoltativa
    `NEW_REGISTRY_WALLET` che sposta la proprietà di un `WhitelistRegistry` appena distribuito, e nient'altro.
    Andare su mainnet con una chiave grezza che non migra mai significa che un solo portatile compromesso
    può congelare, trasferire coattivamente o riassegnare i permessi dell'intero registro.

Questo è un runbook operativo, non una modifica dei contratti — il modello `AccessControl`/`Ownable` già presente nei contratti è esattamente ciò che serve a un multisig; nulla di tutto ciò richiede una modifica in Solidity o un nuovo deployment.

### 1. Allestisci il multisig prima di distribuire

Distribuisci prima un [Gnosis Safe](https://safe.global/) (o equivalente) sulla chain di destinazione, con firmatari che siano persone identificate su hardware wallet separati — mai una seconda chiave sulla stessa macchina che ha eseguito `forge script`. Una soglia 3 su 5 è un punto di partenza ragionevole per un operatore di registro; adattala alla tua politica di separazione dei compiti.

### 2. Distribuisci con l'EOA, poi sposta i diritti di amministrazione nella stessa sessione

Esegui lo script di deployment esattamente come documentato sopra — l'EOA deve firmare di persona le transazioni di deployment, con questi script non esiste modo di evitarlo. Subito dopo, nella stessa finestra operativa, per ogni contratto dell'ecosistema:

```solidity
// One transaction pair per AccessControl contract (OrgRegistry, PermissionRegistry,
// EcosystemTrustedIssuersRegistry, PermissionOracle, DappRegistry):
grantRole(DEFAULT_ADMIN_ROLE, safeAddress);
grantRole(OPERATOR_ROLE, safeAddress);
// Only after confirming the Safe can exercise both roles (see step 4):
renounceRole(OPERATOR_ROLE, deployerEoa);
renounceRole(DEFAULT_ADMIN_ROLE, deployerEoa);

// For Ownable contracts (AssetTokenFactory-spawned tokens, EwpgBondDesk-style deployments):
transferOwnership(safeAddress);
```

`AssetTokenFactory.registryWallet` è `immutable` — non può essere ripuntato al Safe dopo il deployment. Se la factory stessa richiede il controllo multisig, il Safe deve essere il deployer della factory (cioè detenere fin dall'inizio il ruolo di `REGISTRY_WALLET_PRIVATE_KEY`, tramite un batch di transazioni Safe anziché un'esecuzione di `forge script` da un EOA), non qualcosa a cui migrare a posteriori.

### 3. Metti un timelock davanti al Safe per le azioni ad alto impatto

Un multisig da solo ferma una singola chiave compromessa; non dà alle parti interessate (emittenti, investitori, altri operatori) un preavviso di una modifica. Per le azioni con un reale raggio d'impatto — revocare la fiducia in `EcosystemTrustedIssuersRegistry`, modificare i permessi di `PermissionRegistry` a livello di piattaforma, ripuntare `PermissionOracle` — fai passare le transazioni del Safe attraverso un [TimelockController](https://docs.openzeppelin.com/contracts/5.x/api/governance#TimelockController) (proponi → ritardo obbligatorio → esegui) invece di eseguirle direttamente. Assegna al timelock `DEFAULT_ADMIN_ROLE` e al Safe `PROPOSER_ROLE`/`EXECUTOR_ROLE` sul timelock, non `DEFAULT_ADMIN_ROLE` direttamente sui contratti di destinazione.

### 4. Verifica prima di rinunciare a qualsiasi cosa

Prima delle chiamate `renounceRole`/`renounceOwnership` del passo 2, esegui una transazione Safe reale e reversibile su ciascun contratto (per esempio un ciclo di concessione/revoca di un permesso senza effetto) e conferma che arrivi on-chain con la soglia di firmatari attesa. `renounceRole` è irreversibile — perdere contemporaneamente l'accesso all'EOA e a un quorum Safe funzionante blocca per sempre le funzioni di amministrazione del contratto.

### 5. Ritira la chiave dell'EOA

Una volta confermato che ruoli/proprietà di ogni contratto sono stati spostati, la chiave privata dell'EOA di deployment non ha più alcun uso legittimo. Distruggila — non archiviarla «per sicurezza»; una chiave di deployment archiviata è esattamente il rischio permanente che l'intero processo esiste per eliminare.
