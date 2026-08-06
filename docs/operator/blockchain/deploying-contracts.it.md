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

## Aggiornamento dei moduli di conformità { #upgrading-compliance-modules }

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```
