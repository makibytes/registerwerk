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

Da Salt und Initcode aus öffentlichen Calldata abgeleitet werden, kann bei Factories, die vor der
Beschränkung von `deployToken`/`deployVault` auf das Register bereitgestellt wurden, jeder die
Calldata des Registers zuerst einspielen und so die Transaktion des Registers selbst scheitern
lassen (`CREATE2 failed`). Das Backend prüft deshalb `predictAddress` vor dem Senden und erneut nach
einem Revert: Liegt dort bereits ein Vertrag und stimmen dessen `assetId()` und `registry()`
überein, übernimmt es diesen Vertrag samt erzeugender Transaktion, statt fehlzuschlagen. Gehört der
Vertrag dort nicht zu uns (etwa weil die Registerberechtigung übergeben wurde), schlägt das
Deployment mit einer Fehlermeldung fehl, die die Adresse nennt.

!!! note "Einführung der auf das Register beschränkten Factory"
    `registryWallet` ist unveränderlich und `bindFactory` jedes Deployers nur einmal aufrufbar;
    die Beschränkung gilt daher erst für eine neue Factory-Generation. Je Chain: die sechs
    Deployer und eine neue `AssetTokenFactory` bereitstellen (`script/Deploy.s.sol` /
    `DeployL2.s.sol` / `DeployTestnet.s.sol`), dann
    `registerwerk.contracts.asset-token-factory.<chain>` aktualisieren und das Backend neu
    starten. Bestehende Token behalten ihre Adressen (sie sind in `asset_deployment` gespeichert);
    noch aktive alte Factories bleiben durch die oben beschriebene Übernahmeprüfung des Backends
    geschützt.

## Compliance-Module aktualisieren

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```
