---
title: Implementación de contratos
---

# Implementación de contratos inteligentes { #deploying-smart-contracts }

## Descripción general { #overview }

Todos los contratos viven en `contracts/` y están compilados con [Foundry](https://book.getfoundry.sh/).

### Arquitectura de contrato { #contract-architecture }

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

## Construir { #build }

```bash
cd contracts
forge build
```

Los artefactos compilados aterrizan en `contracts/out/`. Maven `web3j-maven-plugin` los lee para generar contenedores de Java.

## Pruebas { #test }

```bash
forge test -vvv
forge coverage
```

Objetivo: ≥80% de cobertura de línea.

## Implementar en testnet { #deploy-to-testnet }

```bash
export ETH_SEPOLIA_RPC=https://rpc.sepolia.org
export DEPLOYER_PRIVATE_KEY=0x<key>

forge script script/DeployTestnet.s.sol \
  --rpc-url $ETH_SEPOLIA_RPC \
  --broadcast \
  --verify
```

## Implementar en mainnet { #deploy-to-mainnet }

```bash
forge script script/Deploy.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow   # 1 tx per block for safety
```

## Determinismo de CREATE2 { #create2-determinism }

`AssetTokenFactory` utiliza `CREATE2` con sal `keccak256(abi.encode(assetId, tokenStandard))`. Esto significa:
- el backend puede calcular previamente la dirección del contrato **antes** de que se mine la transacción
- la dirección se almacena como `PENDING` en `asset_deployment` inmediatamente
- la implementación es idempotente: volver a ejecutar la misma implementación producirá la misma dirección

## Actualización de módulos de cumplimiento { #upgrading-compliance-modules }

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```
