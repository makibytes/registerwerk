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

Como el salt y el initcode se derivan de calldata públicos, en las factorías desplegadas antes de
que `deployToken`/`deployVault` quedaran reservadas al registro cualquiera puede reproducir primero
los calldata del registro y hacer que la propia transacción del registro se revierta
(`CREATE2 failed`). Por eso el backend comprueba `predictAddress` antes de enviar y de nuevo tras
una reversión: si ya hay un contrato en esa dirección y sus `assetId()` y `registry()` coinciden,
adopta ese contrato y su transacción de creación en lugar de fallar. Si el contrato no es nuestro
(por ejemplo, porque se traspasó la autoridad del registro), el despliegue falla con un error que
indica la dirección.

!!! note "Implantación de la factoría reservada al registro"
    `registryWallet` es inmutable y el `bindFactory` de cada deployer solo puede llamarse una vez,
    así que la restricción solo se aplica a una nueva generación de factoría. Por cadena: desplegar
    los seis deployers y una nueva `AssetTokenFactory` (`script/Deploy.s.sol` / `DeployL2.s.sol` /
    `DeployTestnet.s.sol`), después actualizar `registerwerk.contracts.asset-token-factory.<chain>`
    y reiniciar el backend. Los tokens existentes conservan sus direcciones (están guardadas en
    `asset_deployment`); las factorías antiguas aún activas siguen protegidas por la comprobación
    de adopción del backend descrita arriba.

## Actualización de módulos de cumplimiento { #upgrading-compliance-modules }

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```

## De una única clave de despliegue a multisig/timelock

!!! warning "Ningún script de este repositorio lo hace por usted"
    Cada archivo `script/Deploy*.s.sol` firma con el único EOA que hay detrás de
    `REGISTRY_WALLET_PRIVATE_KEY` y concede a esa misma dirección `DEFAULT_ADMIN_ROLE` +
    `OPERATOR_ROLE` en `OrgRegistry`, `PermissionRegistry`, `EcosystemTrustedIssuersRegistry`,
    `PermissionOracle` y `DappRegistry`, además de la propiedad `Ownable` de cada token que crea
    `AssetTokenFactory` — de forma permanente, **sin paso de traspaso**. `UpgradeCompliance.s.sol` de
    `EwpgBondDesk` es la única excepción: una variable de entorno opcional `NEW_REGISTRY_WALLET` que
    traslada la propiedad de un `WhitelistRegistry` recién desplegado, y nada más. Pasar a mainnet con una
    clave en bruto que nunca migra significa que un solo portátil comprometido puede congelar,
    transferir a la fuerza o reasignar permisos en todo el registro.

Esto es un manual operativo, no un cambio de contrato — el modelo `AccessControl`/`Ownable` que ya tienen los contratos es exactamente lo que necesita un multisig; nada de esto requiere un cambio de Solidity ni un nuevo despliegue.

### 1. Monte el multisig antes de desplegar

Despliegue primero un [Gnosis Safe](https://safe.global/) (o equivalente) en la cadena de destino, con firmantes que sean personas identificadas en wallets de hardware distintas — nunca una segunda clave en la misma máquina que ejecutó `forge script`. Un umbral de 3 de 5 es un punto de partida razonable para un operador de registro; ajústelo a su propia política de segregación de funciones.

### 2. Despliegue con el EOA y traspase los derechos de administración en la misma sesión

Ejecute el script de despliegue exactamente como se documenta arriba — el EOA tiene que firmar él mismo las transacciones de despliegue, no hay forma de evitarlo con estos scripts. Inmediatamente después, en la misma ventana operativa, para cada contrato del ecosistema:

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

`AssetTokenFactory.registryWallet` es `immutable` — no se puede reapuntar al Safe tras el despliegue. Si la propia factory necesita control multisig, el Safe debe ser el desplegador de la factory (es decir, tener desde el principio el rol de `REGISTRY_WALLET_PRIVATE_KEY`, mediante un lote de transacciones del Safe en lugar de una ejecución de `forge script` con un EOA), y no algo que se migre después.

### 3. Ponga un timelock delante del Safe para las acciones de alto impacto

Un multisig por sí solo detiene una clave comprometida aislada; no da a las partes afectadas (emisores, inversores, otros operadores) un aviso previo de un cambio. Para acciones con un alcance real — revocar la confianza de `EcosystemTrustedIssuersRegistry`, cambiar los permisos de `PermissionRegistry` en toda la plataforma, reapuntar `PermissionOracle` — encamine las transacciones del Safe a través de un [TimelockController](https://docs.openzeppelin.com/contracts/5.x/api/governance#TimelockController) (proponer → retraso obligatorio → ejecutar) en lugar de ejecutarlas directamente. Otorgue al timelock `DEFAULT_ADMIN_ROLE` y al Safe `PROPOSER_ROLE`/`EXECUTOR_ROLE` en el timelock, no `DEFAULT_ADMIN_ROLE` directamente sobre los contratos de destino.

### 4. Verifique antes de renunciar a nada

Antes de las llamadas `renounceRole`/`renounceOwnership` del paso 2, ejecute una transacción real y reversible del Safe contra cada contrato (por ejemplo, un ciclo de concesión/revocación de permiso sin efecto) y confirme que llega a la cadena con el umbral de firmantes esperado. `renounceRole` es irreversible — perder a la vez el acceso al EOA y a un quórum del Safe operativo bloquea para siempre las funciones de administración del contrato.

### 5. Retire la clave del EOA

Una vez confirmado que los roles/la propiedad de cada contrato se han trasladado, la clave privada del EOA desplegador no tiene ningún uso legítimo más. Destrúyala — no la archive «por si acaso»; una clave de despliegue archivada es exactamente el riesgo permanente que todo este proceso existe para eliminar.
