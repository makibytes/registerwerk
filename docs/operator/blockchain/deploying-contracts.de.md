---
title: Verträge bereitstellen
---

# Bereitstellen von Smart Contracts

## Übersicht

Alle Verträge liegen in `contracts/` und werden mit [Foundry](https://book.getfoundry.sh/) kompiliert.

### Vertragsarchitektur

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

## Build

```bash
cd contracts
forge build
```

Kompilierte Artefakte landen in `contracts/out/`. Der Maven-`web3j-maven-plugin` liest diese, um Java-Wrapper zu generieren.

## Test

```bash
forge test -vvv
forge coverage
```

Ziel: ≥80 % Zeilenabdeckung.

## Im Testnetz bereitstellen

```bash
export ETH_SEPOLIA_RPC=https://rpc.sepolia.org
export DEPLOYER_PRIVATE_KEY=0x<key>

forge script script/DeployTestnet.s.sol \
  --rpc-url $ETH_SEPOLIA_RPC \
  --broadcast \
  --verify
```

## Im Mainnet bereitstellen

```bash
forge script script/Deploy.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow   # 1 tx per block for safety
```

## CREATE2-Determinismus

`AssetTokenFactory` verwendet `CREATE2` mit dem Salt `keccak256(abi.encode(assetId, tokenStandard))`. Das bedeutet:
- Das Backend kann die Vertragsadresse **bevor** die Transaktion gemined wird vorab berechnen
- Die Adresse wird sofort als `PENDING` in `asset_deployment` gespeichert
- Die Bereitstellung ist idempotent — ein erneuter Lauf derselben Bereitstellung erzeugt dieselbe Adresse

## Compliance-Module aktualisieren

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```
