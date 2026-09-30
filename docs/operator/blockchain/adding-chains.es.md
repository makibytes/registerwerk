---
title: Agregar nuevas cadenas
---

# Agregar nuevas cadenas { #adding-new-chains }

Los clientes de la cadena backend se pueden registrar en tiempo de ejecución. La indexación EVM también requiere una configuración de red de graph-node
y un objetivo de implementación compatible con fuentes de contrato explícitas.

## Tipos de cadena admitidos { #supported-chain-types }

| Tipo | Ejemplos |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, cualquier compatible con EVM |
| `SOLANA` | Solana Mainnet, Devnet |

## Agregar una cadena EVM (tutorial completo) { #adding-an-evm-chain-full-walkthrough }

### 1. Regístrese mediante la API de administración { #1-register-via-admin-api }

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "OPTIMISM_MAINNET",
    "displayName": "Optimism",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 10,
    "rpcUrl": "https://mainnet.optimism.io",
    "wsUrl": "wss://mainnet.optimism.io",
    "blockExplorerUrl": "https://optimistic.etherscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/optimism-mainnet"
  }'
```

### 2. Implementar contratos { #2-deploy-contracts }

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Agregar a la configuración de graph-node { #3-add-to-graph-node-config }

Consulte [Configuración del indexador](../configuration/indexers.md) para TOML y cambios en docker-compose.

### 4. Reinicie graph-node con la nueva red { #4-restart-graph-node-with-the-new-network }

La API de administración de implementación no puede aceptar un manifiesto para la nueva red hasta que graph-node haya vuelto a cargar
su configuración de cadena:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Verifique que graph-node esté en buen estado antes de continuar.

### 5. Configure e implemente el subgrafo { #5-configure-and-deploy-the-subgraph }

Configure cada fuente `*_OPTIMISM` descrita en [The Graph](../indexers/the-graph.md), luego:

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

El subgrafo es una proyección de evento provisional. No establece la finalidad de la cadena, el efecto legal
, el estado del registro autorizado ni la identidad del código implementado.

### 6. Activar la actualización del cliente { #6-trigger-client-refresh }

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

El `BlockchainClientRegistry` crea un nuevo cliente Web3j para la cadena inmediatamente.

## RPC de respaldo { #fallback-rpcs }

Puede configurar varias URL de RPC para conmutación por error. La configuración de la cadena almacena `fallback_rpc_urls` como una lista separada por comas. Si el RPC principal falla, el registro prueba las alternativas en orden.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```

## Gobernanza y confianza de los nodos RPC

El nodo RPC que escucha el registro determina qué estado de la cadena cree; por ello los nodos se gobiernan como claves:

- **Autenticación reforzada y segundo aprobador.** Añadir, modificar, activar, desactivar, fijar (`exclusive`) y eliminar un nodo (`/api/v1/admin/chains/{chainId}/nodes...`), así como restablecer la fijación del genesis, exigen un token de autenticación reforzada y un segundo aprobador (`X-Dual-Control-Token`, motivo `RPC_NODE_CHANGE`). El evento de auditoría `RPC_NODE_*` registra al operador, al segundo aprobador y la URL anterior y la nueva; los secretos de las URL (userinfo, valores de query, segmentos de ruta similares a una clave) se ocultan.
- **Solo https.** Las URL de nodo deben usar `https`/`wss`. `http`/`ws` en claro solo se acepta para hosts de bucle local o privados y únicamente con `registerwerk.rpc.allow-insecure-private=true` (entornos de demostración). `chain_config.rpc_allowed_hosts` (separado por comas, se admite `*.example.org`) restringe opcionalmente los hosts de una cadena. Las reglas se aplican al añadir y al cambiar la URL.
- **Identidad de la cadena.** Al añadir y en cada ronda de salud, `eth_chainId` debe coincidir con el `chain_config.chain_id` fijado y el hash del bloque genesis con `chain_config.genesis_hash` (Solana: `getGenesisHash`). El primer nodo coincidente fija el hash genesis. Un nodo discrepante se rechaza al añadirlo; más tarde se marca como no sano con el motivo `CHAIN_MISMATCH` y nunca se enruta, ni siquiera como último recurso. Tras un restablecimiento legítimo de una devnet, la fijación se borra con `POST /api/v1/admin/chains/{chainId}/nodes/genesis-pin/reset` (autenticación reforzada y segundo aprobador); la siguiente ronda fija el nuevo genesis.
- **El mejor bloque es una mediana.** El retraso se mide frente a la altura mediana de los candidatos de enrutamiento (la menor de dos), no frente al máximo. Un nodo que supere esa referencia en más de `registerwerk.rpc.max-plausible-jump-blocks` (1000 por defecto) se pone en cuarentena (`IMPLAUSIBLE_HEIGHT`).
- **Conmutación rápida.** Un nodo deja de estar sano en la primera sonda fallida y necesita dos sondas correctas consecutivas para volver (`health_reason` `RECOVERING`). Con dos o más nodos enrutables, las lecturas con fallo de transporte se reintentan en el siguiente nodo dentro de la misma llamada (como máximo tres intentos); el nodo fallido queda en último lugar durante 30 segundos. Las transacciones se difunden a un solo nodo. Tras aceptar un nodo una difusión, las lecturas prefieren ese nodo durante dos minutos (lectura de las propias escrituras). Las lecturas lending de varias llamadas (cotizaciones, actualización de posiciones) se ejecutan sobre un único número de bloque, y un factor de salud lending solo es fiable si el nodo usado va como máximo un bloque por detrás.
- **Confirmación por una segunda fuente.** Antes de completar una transacción que modifica el registro (transferencia forzosa, burn, mint, congelación, pausa, claims, registro de identidades, propiedad, roles; configurable con `registerwerk.blockchain.tx.second-source-methods`), al menos dos nodos sanos deben devolver mediante `eth_getTransactionByHash` el mismo hash de bloque, el mismo contrato de destino y el mismo remitente. Una discrepancia retiene la transacción (`registerwerk.confirmation.second_source_mismatch`, log ERROR). Con menos de dos nodos sanos la transacción continúa y `registerwerk.confirmation.single_source` la cuenta. Las comisiones del nodo principal se contrastan con un segundo nodo: comisión = `min(A, B x 1,5)`.
- **Los parámetros de finalidad** proceden únicamente de `chain_config` y `registerwerk.blockchain.tx.*`. Un nodo chaincache sigue siendo fuente de hashes de bloque, pero su nivel SAFE/FINALIZED autodeclarado queda limitado por la profundidad o las etiquetas configuradas. Qué nivel de finalidad basta legalmente para una inscripción según el modelo de cadena es una decisión abierta (T4-08).
- **El chain id debe estar fijado.** La firma rechaza toda transacción en una cadena EVM cuyo `chain_config.chain_id` esté vacío (`chain id unpinned`). Al arrancar, el backend registra un ERROR con las cadenas EVM habilitadas que tienen despliegues CONFIRMED pero ningún chain id, y expone la métrica `registerwerk_evm_chains_unpinned` (alerta `EvmChainIdUnpinned`). Deliberadamente no hay relleno automático: establezca el id de la red de forma explícita (`PATCH /api/v1/admin/chains/{id}` con `chainId`) antes de la siguiente transacción.
