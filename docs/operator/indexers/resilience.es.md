---
title: Resiliencia y recuperación
---

# Resiliencia del indexador

Esta página describe cómo el registro detecta brechas en el indexador, detecta y se recupera de reorganizaciones de cadena (reorgs) y se recupera de interrupciones. Estos procedimientos no establecen corrección jurídica — véase `docs/operator/indexers/the-graph.md` para lo que `synced: true` de un subgraph significa y no significa. Para lo que ocurre *aguas abajo* de un reorg detectado — el diario de efectos, la compensación automática y la puerta de política que puede congelar un activo a la espera de revisión — véase [Política de finalidad y compensación de reorgs](finality-and-compensation.md).

!!! note "Corregido según la implementación real"
    Una versión anterior de esta página describía un esquema `chain_head_block`/`latest_indexed_block`, una escalera de niveles de salud `DEGRADED`/`CRITICAL` y un endpoint `POST /backfill` — nada de eso existe en esta base de código. Esta página describe ahora lo que realmente está implementado.

!!! note "De dos a tres niveles"
    Esta página describía originalmente un `token_transfer.finality_status` de dos niveles: `PROVISIONAL`/`FINAL`/`ORPHANED`. Esa columna se amplió al `finality.api.FinalityLevel` de tres niveles (`PROVISIONAL`/`SAFE`/`FINALIZED`/`ORPHANED`) compartido con los productos hermanos chaincache y chaincheck — `FINAL` pasó a ser `FINALIZED`, y un nuevo nivel `SAFE` se sitúa entre PROVISIONAL y FINALIZED para las cadenas que exponen un punto de control intermedio (p. ej., la etiqueta de bloque `safe` de Ethereum, `ACCEPTED_ON_L2` de Starknet). Todos los ejemplos de esta página se han actualizado a la columna y la enumeración actuales.

## Seguimiento del estado del indexador

El backend mantiene una tabla `indexer_state`, una fila por `(chain_config_id, indexer_type)`:

```sql
SELECT chain_config_id, indexer_type, status,
       last_synced_block, last_final_block, last_synced_at,
       consecutive_errors, last_error
FROM indexer_state
ORDER BY last_synced_at ASC NULLS FIRST;
```

- `last_synced_block` — el cursor de cabecera/provisional: el bloque más alto del que el indexador ha leído transferencias, hayan sido o no finalizadas esas filas desde entonces.
- `last_final_block` — el cursor confirmado: el bloque más alto cuyas filas `token_transfer` han superado todas la profundidad de confirmación configurada y se han verificado frente a un hash/estado canónico obtenido de nuevo (EVM, Starknet). Siempre `<= last_synced_block`. Nulo en las cadenas finales al escribir (Solana/Stellar/Canton — véase abajo), que nunca tienen una ventana sin liquidar que seguir.
- `status` — `ACTIVE`, `PAUSED` o `ERROR`. Un indexador en `ERROR` con `consecutive_errors >= 10` (5 para Canton) deja de ejecutarse hasta que se restablece manualmente (véase [Recuperación manual](#manual-recovery)) — no hay autorreparación automática más allá de ese punto.

## Detección y recuperación de reorgs de cadena

Cada cadena EVM (mediante graph-node) y cada cadena Starknet vuelve a verificar su ventana todavía sin liquidar (PROVISIONAL o SAFE) en cada ciclo de sincronización, mediante `ReorgGuard`:

- **EVM** — `token_transfer.block_hash` se registra para las filas dentro de la profundidad de confirmación configurada (`registerwerk.blockchain.tx.confirmations-by-chain`); `ReorgGuard` vuelve a obtener el hash canónico de cada uno de esos bloques mediante `_meta(block: {number})` de graph-node y lo compara. Un bloque que sigue coincidiendo se asciende un paso (PROVISIONAL → SAFE → FINALIZED, con una profundidad de confirmación etiquetada `safe` distinta de la `finalized`); una discrepancia marca como `ORPHANED` todas las filas desde el punto de bifurcación (nunca se borran — es un registro regulado con requisito de pista de auditoría) y rebobina `last_synced_block`/`last_final_block` a `fork_block - 1`, de modo que el siguiente ciclo reindexa el rango afectado. Una cadena cuyo `ChainConfig.finalitySource` es `CHAINCACHE` (véase [Integración con chaincache](../blockchain/chaincache-integration.md)) obtiene esta reverificación de `ChaincacheFinalityProbe` — una llamada al propio `GET /{chain}/api/blocks/{number}/finality` de la carga de chaincache de esa cadena — en lugar de la obtención por RPC anterior; cualquier fallo de la sonda (inalcanzable, 401, 404, 5xx) recurre a la vía RPC en lugar de fabricar un reorg falso, de modo que una breve indisponibilidad de chaincache degrada el seguimiento de finalidad al comportamiento de RPC simple en lugar de romperlo. Con independencia de qué sonda responda a esta consulta, la cadena recibe además el flujo de eventos duradero y push de chaincache (`ChaincacheDurableStreamManager`) como fuente *adicional* y sin brechas de observaciones `BLOCK`/`RETRACTION` que alimentan el mismo libro `block_finality` descrito abajo — ambas son complementarias, no excluyentes: el flujo duradero puede observar una retractación antes de que la reverificación por sondeo la hubiera detectado, y la vía por sondeo sigue funcionando aunque la conexión del flujo duradero se interrumpa brevemente.
- **Starknet** — no se usa ninguna primitiva de hash de bloque; en su lugar, la finalidad de cada fila sin liquidar se vuelve a comprobar mediante el campo `status` de `starknet_getBlockWithTxHashes`: `ACCEPTED_ON_L2` asciende a SAFE, `ACCEPTED_ON_L1` asciende a FINALIZED; un bloque `REJECTED`/`REVERTED` activa la misma vía de huérfano-y-rebobinado que en EVM.
- **Solana** — la finalidad se establece al escribir: las transferencias solo se indexan con `commitment: "finalized"`, y se comprueba el campo `err` de la firma para que una transacción fallida nunca se indexe como transferencia exitosa. No hay una ventana sin liquidar aparte que reverificar.
- **Stellar / Canton** — el cierre del ledger (Stellar/Horizon) y la confirmación del sincronizador (Canton) son finales una vez observados; mismo razonamiento que en Solana.

Cada bloque que la ventana sin liquidar de una cadena llega a tocar — los realmente resondeados arriba — se registra además en `block_finality` (una fila por `(chain_config_id, block_number)`, propiedad del módulo `finality`, alimentada por `ReorgGuard`). Es un libro aparte de `token_transfer` en sí: es la fuente de verdad que consultan `FinalityGate` y la maquinaria de compensación de efectos (véase [Política de finalidad y compensación de reorgs](finality-and-compensation.md)), de modo que nunca necesitan recorrer `token_transfer` ni importar el módulo indexador. `token_transfer.finality_status` sigue siendo una caché desnormalizada del mismo hecho, cómoda para consultar transferencias directamente.

`token_transfer.finality_status ∈ {PROVISIONAL, SAFE, FINALIZED, ORPHANED}` se puede consultar directamente:

```sql
SELECT chain_config_id, finality_status, count(*)
FROM token_transfer
WHERE finality_status <> 'FINALIZED'
GROUP BY chain_config_id, finality_status;
```

Un recuento de `ORPHANED` distinto de cero es esperable de forma transitoria justo después de un reorg real; un recuento que no disminuye en los ciclos siguientes significa que el rango afectado no se reindexa con éxito — revise `indexer_state.last_error` de esa cadena. Un recuento de `SAFE` sostenido (filas que no avanzan a `FINALIZED`) suele significar que el modelo de finalidad de la cadena espera una profundidad de confirmación o una etiqueta de bloque que el nodo RPC configurado no informa — véase la sección del modelo de finalidad de `docs/operator/blockchain/adding-chains.md`.

**Limitación conocida:** no hay política de RPC de confianza/quórum — la respuesta `_meta`/estado de un único endpoint RPC configurado se acepta tal cual para la detección de reorgs. Un nodo RPC defectuoso o rezagado puede producir por sí mismo una señal de reorg falsa; contraste con la configuración RPC de `docs/operator/blockchain/adding-chains.md` antes de tratar una alerta de reorg como un evento de cadena confirmado.

## Supervisión del retraso del indexador

`IndexerMonitorService` se ejecuta cada 5 minutos y publica dos indicadores de Prometheus:

- `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id, indexer_type}` — segundos de época Unix de la última sincronización correcta. Alerte con `time() - <metric> > threshold`.
- `registerwerk_indexer_lag_blocks{chain_config_id, indexer_type}` — bloques entre `last_synced_block` y el mayor `latest_block_number` informado por cualquier `rpc_node` habilitado y sano de esa cadena (reutilizando los datos de cabecera ya en caché de `RpcNodeHealthService`, no una llamada RPC nueva). Ausente — no cero — para una cadena sin nodo sano o sin bloque sincronizado todavía, de modo que una serie ausente significa «sin datos», no «sin retraso».

También publica un evento de auditoría `INDEXER_STALE` cuando un indexador está en `ERROR` o no ha sincronizado en más de 2 horas. Las reglas reales de Prometheus están en `monitoring/alerts/registerwerk.yml`, grupo `registerwerk.critical` (`IndexerStaleCritical`/`IndexerStaleWarning`) y grupo `registerwerk.observability` (`IndexerLagBlocksHigh`, alerta con más de 1000 bloques sostenidos durante 10 minutos o más) — ambas son reglas reales y evaluadas en este repositorio, no ejemplos ilustrativos.

## Recuperación manual { #manual-recovery }

Un indexador que ha alcanzado `consecutive_errors >= 10` (5 para Canton) deja de sincronizar hasta que se restablece.

```bash
# List every indexer's current state
curl -H "Authorization: Bearer $OPERATOR_JWT" http://localhost:48080/api/v1/indexers

# Clear the error state — the next scheduled tick resumes from the existing cursor, no restart required
curl -X POST -H "Authorization: Bearer $OPERATOR_JWT" \
  "http://localhost:48080/api/v1/indexers/<indexer-state-id>/reset"

# Force a full re-sync from genesis instead (only if the existing cursor itself is untrustworthy —
# e.g. after a manual chain-state correction; this re-processes the chain's entire history)
curl -X POST -H "Authorization: Bearer $OPERATOR_JWT" \
  "http://localhost:48080/api/v1/indexers/<indexer-state-id>/reset?fullResync=true"
```

Ambas acciones requieren `REGISTRY_ADMIN` y se auditan (`INDEXER_RESET`). El SQL directo equivalente (p. ej., para una vía de emergencia o con scripts sin la API) sigue siendo:

```sql
SELECT id, chain_config_id, indexer_type, last_synced_block, last_synced_at, status, consecutive_errors, last_error
FROM indexer_state;

UPDATE indexer_state SET status = 'ACTIVE', consecutive_errors = 0, last_error = NULL WHERE id = '<uuid>';
```

## Deduplicación

`token_transfer` tiene restricciones `UNIQUE NULLS NOT DISTINCT` compatibles con particiones sobre `(chain_config_id, tx_hash, log_index, occurred_at)` para EVM/Starknet y sobre `(chain_config_id, tx_hash, slot, occurred_at)` para Solana. Resincronizar desde un bloque anterior es seguro porque cada servicio de sincronización comprueba además la identidad de la transacción antes de insertar, de modo que un rango reprocesado se omite en lugar de duplicarse.

## Modos de fallo y recuperación

| Componente | Fallo | Recuperación |
|---|---|---|
| graph-node | Deja de indexar / informa `hasIndexingErrors` | El backend degrada ese ciclo a «escribir todo como PROVISIONAL, omitir la reverificación de reorgs» en lugar de hacer fallar la sincronización; investigue graph-node directamente |
| RPC de EVM/Starknet/Stellar | Conexión perdida | `consecutive_errors` aumenta; reanuda desde el cursor al reconectar; estado `ERROR` tras 10 fallos consecutivos |
| Solana Yellowstone gRPC | El flujo se cae | El sondeo de respaldo (`SolanaTransferSyncService`) cubre las brechas con su propio cron de 10 minutos, con independencia del estado del flujo |
| Flujo del ledger de Canton | El flujo se cae | Estado `ERROR` tras 5 fallos consecutivos (umbral más bajo que en otras cadenas — véase `CantonTransferSyncService`) |

## Reindexación del subgraph (solo EVM)

Si un subgraph tiene errores fatales y no puede recuperarse automáticamente, no elimine el despliegue activo. Renderice y despliegue una versión nueva bajo el nombre de grafo de mainnet configurado:

```bash
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Espere a que la nueva versión alcance la cabecera de la cadena y compare de forma independiente su rango de eventos antes de permitir que se confíe en ella aguas abajo. Conserve la configuración y los artefactos anteriores; si hace falta revertir, vuelva a desplegar la configuración aprobada anteriormente bajo una etiqueta de versión nueva en lugar de borrar de forma destructiva el historial de cualquiera de las versiones.
