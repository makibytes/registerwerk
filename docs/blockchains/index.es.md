---
title: Blockchains admitidas
description: Todas las redes blockchain admitidas, sus capacidades y cómo se conecta Registerwerk a ellas.
---

# Blockchains admitidas

Registerwerk admite ocho tipos de blockchain, en redes principales y de prueba. La conectividad con las cadenas la gestiona el `BlockchainClientRegistry` del módulo `blockchain`, que selecciona el mejor nodo RPC disponible para cada petición.

---

## Referencia rápida

| Tipo de cadena | Estándar(es) de token | Biblioteca cliente | Redes | Estado |
|---|---|---|---|---|
| [Ethereum y EVM](evm.md) | ERC-20/721/1155/3525/3643/4626/7540 | Web3j | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism | Implementación presente; madurez para producción no verificada |
| [EVM confidencial](confidential-evm.md) | CONF_ERC20, CONF_ERC3643 | Web3j + SDK de Zama | Fhenix, Inco | Implementación presente; madurez para producción no verificada |
| [Solana](solana.md) | SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL | Solanaj | mainnet-beta, devnet | Integración presente; madurez para producción no verificada |
| [Canton / DAML](canton.md) | DAML_BOND_*, CANTON_TOKEN | Cliente Java de DAML | Canton Network, devnet | Implementación opcional (`-Pcanton`); madurez para producción no verificada |
| [StarkNet](starknet-stellar.md) | STARKNET_ERC20, STARKNET_ERC3525 | Starknet4j propio | mainnet, sepolia | ⚠️ Marcador de posición |
| [Stellar](starknet-stellar.md) | STELLAR_ASSET | SDK Java de Horizon | mainnet, testnet | ⚠️ Marcador de posición |

---

## `BlockchainClientRegistry`

El `BlockchainClientRegistry` (`blockchain/api/`) es el componente central de toda la conectividad con las cadenas. Para las cadenas EVM mantiene tres niveles de clientes:

1. **Grupo de nodos** (máxima prioridad) — lo alimenta el `RpcNodeHealthService` tras cada ronda de comprobación de salud. Elige el nodo más sano y de menor latencia
2. **Clientes individuales dinámicos** — un cliente por cada fila `chain_config` habilitada (heredado, renovado con `ChainConfigUpdatedEvent`)
3. **Clientes estáticos** — cargados al arrancar desde las propiedades de `application.yml`

### Algoritmo de selección de nodos

Para el grupo de nodos, el registro aplica esta lógica de selección:

```
1. If any enabled node has exclusive=true → use only exclusive-enabled nodes
2. Otherwise → use all enabled nodes
3. From candidates: prefer healthy nodes with smallest block lag
4. If no healthy candidates → use least-bad (fewest failures, most recent success)
5. If ALL nodes disabled → throw IllegalStateException
```

Esto proporciona una conmutación automática entre varios proveedores de RPC sin intervención del operador.

---

## Añadir una cadena nueva

Para añadir una cadena compatible con EVM:

1. Añada la cadena a la enumeración `Chain` en `chain/api/Chain.java`
2. Añada la URL de RPC en `application.yml` bajo `registerwerk.evm.chains.<chainName>.<network>.rpcUrl`
3. Despliegue los contratos de Registerwerk en la cadena nueva (con el servicio de despliegue existente)
4. Configure el registro `chain_config` mediante la API de administración

Añadir una cadena no EVM exige implementar la interfaz de fábrica de clientes correspondiente y registrar el cliente en `BlockchainConfig`.

---

## Formato del identificador de cadena

En el sistema las cadenas se identifican mediante `ChainDescriptor(chain, network)`:

```java
new ChainDescriptor(Chain.ETHEREUM, Network.MAINNET)
// → identifier: "ETHEREUM_MAINNET"
```

La cadena de texto `identifier` sirve de clave en los mapas de clientes dinámicos y en `asset_deployment.chain_identifier`, para enlazar los despliegues con la cadena correcta.
