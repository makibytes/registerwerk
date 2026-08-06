---
title: Solana (gRPC de Yellowstone)
---

# Indexador de Solana — Yellowstone gRPC

El indexador Solana utiliza **Yellowstone**, un complemento de Geyser que transmite actualizaciones de cuentas y transacciones en tiempo real a través de gRPC. Un respaldo de sondeo cubre los períodos en los que la transmisión gRPC no está disponible.

## Arquitectura

```
Solana Validator (with Yellowstone Geyser plugin)
        |
        | (gRPC stream)
        v
  indexer/solana (Node.js service)
        |
        | (REST / WebSocket)
        v
  Backend Spring Boot
```

## Requisitos previos

- Un punto final Solana RPC compatible con **Yellowstone gRPC**
  - Opciones alojadas: [Helius](https://helius.dev), [Triton](https://triton.one)
  - Autohospedado: requiere un validador de Solana con el complemento de Yellowstone instalado

## Configuración

`indexer/solana/yellowstone.yaml`:

```yaml
endpoint: "${SOLANA_YELLOWSTONE_ENDPOINT}"
x_token: "${SOLANA_YELLOWSTONE_TOKEN}"

subscriptions:
  - type: transaction
    accountInclude:
      - "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"  # SPL Token program
    commitment: confirmed

polling:
  enabled: true
  fallback_rpc: "${SOLANA_MAINNET_RPC_URL}"
  poll_interval_seconds: 2
  max_blocks_per_poll: 10
```

Establecido en `.env`:

```bash
SOLANA_YELLOWSTONE_ENDPOINT=https://your-yellowstone-endpoint.helius.xyz:2083
SOLANA_YELLOWSTONE_TOKEN=your_api_token
SOLANA_MAINNET_RPC_URL=https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
SOLANA_DEVNET_RPC_URL=https://devnet.helius-rpc.com/?api-key=YOUR_KEY
```

## Iniciando el indexador Solana

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

O para el desarrollo local:

```bash
cd indexer/solana && npm install
npm start
```

## Seguimiento de tokens SPL

El indexador de Solana rastrea los eventos del programa de tokens SPL:
- `Transfer`: transferencias de tokens entre cuentas
- `MintTo`: acuñación de tokens
- `Burn`: destrucción de tokens

Filtra eventos solo aquellos que involucran direcciones de token mint registradas en el backend. Se ignora la actividad del token SPL no relacionada.

## Respaldo de sondeo

Si la transmisión gRPC de Yellowstone se desconecta o deja de estar disponible, el indexador cambia automáticamente al modo de sondeo:

1. Sondea `getSignaturesForAddress` para el programa de token SPL en el intervalo configurado
2. Obtiene los detalles completos de la transacción para cada nueva firma
3. Analiza eventos de token de los registros de transacciones
4. Vuelve a gRPC tan pronto como la transmisión se vuelve a conectar

El respaldo de sondeo es menos eficiente pero garantiza que no se pierdan eventos durante las interrupciones del proveedor.

## Monitoreo

El indexador de Solana expone un punto final de estado:

```bash
curl http://localhost:3001/health
```

Respuesta:

```json
{
  "status": "healthy",
  "mode": "grpc",
  "latestSlot": 285614923,
  "lastEventAt": "2025-04-06T12:00:00Z"
}
```

Si `mode` muestra `polling`, la transmisión de gRPC está inactiva y el respaldo está activo. Investigue el punto final de Yellowstone.

## Agregar Solana Devnet

El indexador devnet se ejecuta como una instancia separada:

```bash
SOLANA_NETWORK=devnet \
  SOLANA_YELLOWSTONE_ENDPOINT=https://devnet-yellowstone.example.com:2083 \
  SOLANA_YELLOWSTONE_TOKEN=your_devnet_token \
  SOLANA_DEVNET_RPC_URL=https://api.devnet.solana.com \
  npm start
```

O agregue un segundo servicio en el archivo Docker Compose con `SOLANA_NETWORK=devnet`.

# Solana — Indexador Yellowstone gRPC

Las transferencias de Solana se monitorean a través de [Yellowstone Dragon's Mouth](https://github.com/rpcpool/yellowstone-grpc), un proxy gRPC para la Geyser Plugin Interface de Solana.

## Iniciando Yellowstone

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Configure `YELLOWSTONE_UPSTREAM_ENDPOINT` en un punto final habilitado para Geyser:
- [Helius](https://helius.dev)
- [Triton One](https://triton.one)
- Un validador de Solana autohospedado con el complemento de Yellowstone

## Cómo funciona

`SolanaTransferSyncService` en el backend:
1. Abre una suscripción gRPC a Yellowstone al inicio (`@PostConstruct`)
2. Filtra transacciones que involucran cualquier dirección mint de SPL conocida
3. Analiza transferencias y upserts en `token_transfer`

Si la transmisión de gRPC se interrumpe, el servicio se vuelve a conectar automáticamente.

## Respaldo de sondeo

Un trabajo independiente `@Scheduled` se ejecuta cada 10 minutos:
- Llama a `getSignaturesForAddress` para cada mint SPL conocido
- Rellena las brechas causadas por el tiempo de inactividad de la transmisión
- La deduplicación mediante la restricción UNIQUE evita el doble conteo

## Registro de un nuevo token SPL

Cuando se crea una implementación de activos de Solana a través de la API, el backend comienza automáticamente a monitorear su dirección mint. No se necesita configuración manual.
