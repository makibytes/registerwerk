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
