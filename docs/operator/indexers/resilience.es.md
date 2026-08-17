---
title: Resiliencia y recuperación
---

# Resiliencia del indexador

Esta página describe cómo el registro detecta brechas en el indexador, se recupera de interrupciones y compara
rangos de eventos provisionales. Estos procedimientos no establecen la finalidad de la cadena ni la corrección legal.

## Seguimiento del estado del indexador

El backend mantiene una tabla `indexer_state` que registra el último bloque indexado exitosamente para cada cadena:

```sql
SELECT chain_id, network_name, latest_indexed_block,
       chain_head_block,
       (chain_head_block - latest_indexed_block) AS lag_blocks,
       last_updated_at
FROM indexer_state
ORDER BY lag_blocks DESC;
```

El backend sondea la API de estado de indexación de graph-node cada 30 segundos y actualiza esta tabla.

## Detección de brechas

Se produce una brecha cuando el indexador cae detrás del cabezal de la cadena. El backend clasifica el retraso como:

| Retraso (bloques) | Estado | Acción |
|------------|--------|--------|
| 0–5 | OK | Funcionamiento normal |
| 6–20 | WARN | Advertencia registrada, se activa la alerta de Prometheus |
| 21–100 | DEGRADED | El panel muestra una advertencia, se envía un correo electrónico al operador |
| 100+ | CRITICAL | `/actuator/health` devuelve `DOWN`, se activa la alerta PagerDuty |

## Procedimientos de recuperación

### Recuperación de graph-node (EVM)

Si graph-node se queda atrás debido a un tiempo de inactividad de RPC:

1. Verifique los registros de graph-node en busca de errores:

   ```bash
   docker compose logs --tail=100 graph-node | grep -i "error\|panic"
   ```

2. Verifique que se pueda acceder al punto final RPC:

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

3. Si el RPC no funciona, actualice `.env` con un RPC alternativo y reinicie:

   ```bash
   docker compose restart graph-node
   ```

4. Supervise el progreso de la recuperación en `http://localhost:8030`.

### Reindexación de subgrafos

Si un subgrafo tiene errores fatales y no se puede recuperar automáticamente, no elimine la implementación activa. Renderice e implemente una versión nueva bajo el nombre de subgrafo de la red principal configurado:

```bash
# Configure every *_MAINNET singleton and multi-instance list with its deployment block,
# then render, validate and deploy a uniquely labelled fresh version. Graph Node retains
# the prior version while ewpg/ethereum-mainnet indexes the replacement.
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Espere a que la nueva versión llegue al cabezal de la cadena, luego compare su rango de eventos de forma independiente antes de permitir la dependencia posterior. Mantenga la configuración y los artefactos anteriores. Si es necesaria la reversión, vuelva a implementar esa configuración previamente aprobada bajo una nueva etiqueta de versión; esto crea una versión nueva en lugar de eliminar destructivamente cualquiera de los historiales.

### Recuperación del indexador de Solana

Si el indexador de Solana omitió eventos durante una interrupción de gRPC:

1. Verifique la última ranura procesada exitosamente en la tabla de estado del indexador
2. El respaldo de sondeo del indexador reprocesa las ranuras automáticamente cuando se vuelve a conectar
3. Si la brecha es demasiado grande (>10.000 ranuras), active un reabastecimiento manual:

   ```bash
   curl -X POST http://localhost:3001/backfill \
     -H "Content-Type: application/json" \
     -d '{"fromSlot": 285600000, "toSlot": 285614923}'
   ```

## Monitoreo de alertas

Configure las reglas de alertas de Prometheus en `monitoring/alerts.yml`:

```yaml
groups:
  - name: indexer
    rules:
      - alert: IndexerLagHigh
        expr: indexer_lag_blocks > 20
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Indexer lag > 20 blocks on {{ $labels.chain }}"

      - alert: IndexerLagCritical
        expr: indexer_lag_blocks > 100
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Indexer lag CRITICAL on {{ $labels.chain }}"

      - alert: GraphNodeDown
        expr: up{job="graph-node"} == 0
        for: 1m
        labels:
          severity: critical
```

# Resiliencia y recuperación

## Modos de fallo y recuperación

| Componente | Fallo | Recuperación |
|---|---|---|
| graph-node | Deja de indexar | Al reiniciar, continúa desde `last_indexed_block` |
| Nodo EVM RPC | Conexión perdida | `GRAPH_ETHEREUM_REQUEST_RETRIES=10`; RPC de respaldo configurables |
| Backend ↔ graph-node | No se puede acceder a GraphQL | `consecutive_errors` se incrementa; se reanuda desde el cursor al volver a conectar |
| GRPC de Yellowstone | Interrupciones del stream | El backend se vuelve a conectar; el trabajo de sondeo llena las brechas |
| Solana RPC | El sondeo falla | `indexer_state.status = ERROR`; alertas de monitor después de 2 h |

## El monitor del indexador

`IndexerMonitorService` comprueba cada 5 minutos si `indexer_state.last_synced_at` tiene más de 2 horas. Si es así, publica un evento de auditoría `INDEXER_STALE`.

## Recuperación manual

Si un indexador se retrasa significativamente:

```bash
# Check current cursor
SELECT chain_config_id, indexer_type, last_synced_block, last_synced_at, status
FROM indexer_state;

# Reset cursor to force full re-sync (use with care)
UPDATE indexer_state SET last_synced_block = 0 WHERE chain_config_id = '<uuid>';
```

Luego reinicie el servicio de sincronización relevante o el backend.

## Deduplicación

Todos los eventos se almacenan con una restricción UNIQUE en `(chain_config_id, tx_hash, log_index)`. Volver a sincronizar desde un bloque anterior es seguro: los duplicados se ignoran silenciosamente (`ON CONFLICT DO NOTHING`).
