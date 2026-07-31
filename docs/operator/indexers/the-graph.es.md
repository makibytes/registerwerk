---
title: The Graph (indexador EVM)
---

# The Graph: indexación EVM { #the-graph-evm-indexing }

Registerwerk utiliza `graph-node` para crear proyecciones provisionales derivadas de eventos para contratos
EVM configurados. Las entidades de subgrafo no son certificaciones de finalidad de cadena, entradas de registro legal, evidencia de liquidación legal ni prueba de identidad del código implementado. Concilie la cadena configurada, las confirmaciones, la implementación del contrato y el registro legal autorizado antes de confiar en ellos.

## Instalar y verificar { #install-and-verify }

```bash
cd indexer/evm/subgraph
npm install
npm test
```

`npm test` comprueba la paridad de eventos/ABI registrada con los artefactos de Forge, prueba la representación del manifiesto,
ejecuta la generación de código de Graph (`graph codegen`) y compila cada mapeo.

## Configuración de implementación requerida { #required-deployment-configuration }

El destino de implementación selecciona un sufijo de entorno:

| Objetivo | Red de Graph | Sufijo |
|---|---|---|
| `mainnet` | `mainnet` | `MAINNET` |
| `sepolia` | `sepolia` | `SEPOLIA` |
| `polygon` | `polygon` | `POLYGON` |
| `polygon-amoy` | `polygon-amoy` | `POLYGON_AMOY` |
| `base` | `base` | `BASE` |
| `base-sepolia` | `base-sepolia` | `BASE_SEPOLIA` |
| `arbitrum-one` | `arbitrum-one` | `ARBITRUM` |
| `arbitrum-sepolia` | `arbitrum-sepolia` | `ARBITRUM_SEPOLIA` |
| `avalanche` | `avalanche` | `AVALANCHE` |
| `avalanche-fuji` | `avalanche-fuji` | `AVALANCHE_FUJI` |
| `optimism` | `optimism` | `OPTIMISM` |
| `optimism-sepolia` | `optimism-sepolia` | `OPTIMISM_SEPOLIA` |

Para cada sufijo, configure las cuatro fuentes singleton siguientes. Su bloque de inicio tiene como valor predeterminado cero, pero los operadores siempre deben usar el bloque de implementación real para hacer explícito el alcance de la reproducción. Cada fuente
tiene procedencia independiente: no copie el bloque de una fábrica en los otros campos de origen
a menos que los recibos de implementación realmente demuestren que comparten ese bloque.

```dotenv
ASSET_TOKEN_FACTORY_ADDRESS_SEPOLIA=0x...
ASSET_TOKEN_FACTORY_START_BLOCK_SEPOLIA=120
REPO_MARKET_FACTORY_ADDRESS_SEPOLIA=0x...
REPO_MARKET_FACTORY_START_BLOCK_SEPOLIA=130
DVP_SETTLEMENT_ADDRESS_SEPOLIA=0x...
DVP_SETTLEMENT_START_BLOCK_SEPOLIA=140
CONFIDENTIAL_FACTORY_ADDRESS_SEPOLIA=0x...
CONFIDENTIAL_FACTORY_START_BLOCK_SEPOLIA=150
```
Las implementaciones de BondDesk, Stablecoin AMM y RepoVault no pueden descubrirse de forma fiable a través de la fábrica. Enumere
cada instancia explícitamente como `address@deploymentBlock`, separadas por comas:

```dotenv
BOND_DESK_INSTANCES_SEPOLIA=0xDesk1@123,0xDesk2@456
STABLECOIN_AMM_INSTANCES_SEPOLIA=0xAmm1@123,0xAmm2@456
REPO_VAULT_INSTANCES_SEPOLIA=0xVault1@123,0xVault2@456
```

Si el operador configura cero instancias para un rol, establezca su lista exactamente en `NONE`. Esta es una afirmación del operador
sobre la configuración, no evidencia de que no exista ninguna implementación en la cadena. Una lista no configurada
o vacía falla en modo cerrado (fail closed). El renderizador también rechaza direcciones cero, bloques con formato incorrecto y una dirección
reutilizada por cualquier otra fuente estática.

## Deploy { #deploy }

```bash
SUBGRAPH_VERSION_LABEL=sepolia-20260729-01 ./indexer/evm/deploy-subgraph.sh sepolia
```

Utilice `SUBGRAPH_VALIDATE_ONLY=true` para renderizar, generar y compilar sin enviar una implementación a graph-node.
`all` procesa todos los objetivos de la tabla y, por lo tanto, requiere configuración para
cada sufijo. Una implementación real también requiere `SUBGRAPH_VERSION_LABEL`; elija una etiqueta nueva para
cada implementación en ese nombre de subgrafo. El script contenedor rechaza una etiqueta ausente; el operador debe asegurarse de que la etiqueta indicada sea nueva. Mantenga disponible la versión anterior hasta que el reemplazo haya alcanzado
la cabeza de la cadena y haya superado la reconciliación independiente del rango de eventos.

AssetTokenFactory crea fuentes de datos de tokens dinámicos a partir de `TokenDeployed` y `VaultDeployed`.
De manera similar, RepoMarketFactory crea fuentes de RepoMarket a partir de `MarketCreated`. Las nuevas instancias
de los tres tipos de contrato enumerados explícitamente requieren una actualización de la lista y una nueva implementación del subgrafo.
Las direcciones emitidas por la fábrica, los ID de activos, las referencias de tokens, los parámetros del oráculo y los bloques de observación
se almacenan como afirmaciones basadas en eventos. No verifican el bytecode implementado, la procedencia de la implementación ni el vínculo
con un registro de la base de datos de la aplicación.

## Migración y reproducción de proyección { #projection-migration-and-replay }

Las entidades de importe nocional por propietario/ranura de ERC-3525 y las de ciclo de vida de solicitud de ERC-7540 requieren el orden de eventos desde la implementación del contrato. Las filas `HolderBalance` existentes para ERC-3525 contaban por ID de token y no pueden convertirse en importes nocionales. No las copie en `Erc3525OwnerSlotBalance`.

Para esta revisión de esquema, implemente una nueva versión de subgrafo y reproduzca cada fuente desde su verdadero bloque de implementación. Una proyección `INCOMPLETE` no puede reconstruir propietarios, ranuras, valores, tipos de solicitud
o configuraciones de mercado de RepoVault anteriores que falten. Cada proyección de RepoVault permanece
`INCOMPLETE` a menos que la procedencia de la implementación y la reproducción completa se demuestren fuera de este subgrafo; simplemente
observar el primer evento en una dirección estática configurada no aporta esa prueba. Mantenga disponible la implementación anterior
para poder revertir hasta que la nueva proyección haya alcanzado la cabeza de la cadena y se haya reconciliado de forma independiente.

Los importes `Allocated` y `Deallocated` de RepoVault se proyectan solo como flujo de caja neto firmado.
La desasignación puede exceder la asignación anterior debido a intereses o a la materialización de pérdidas, por lo que este valor
no es principal pendiente, posición de mercado escalada ni NAV, y un total negativo no constituye por sí mismo
una inconsistencia.

## Monitorear y consultar { #monitor-and-query }

```bash
curl -s http://localhost:8030/graphql \
  -d '{"query":"{indexingStatuses{subgraph synced health chains{network latestBlock{number}}}}"}' \
  | jq '.data.indexingStatuses[]'
```

`synced: true` describe únicamente el progreso de graph-node; no es una señal de finalidad ni de efecto legal.
Consulte una implementación en `http://localhost:8000/subgraphs/name/<subgraph-name>`.

Los fallos habituales son la limitación de velocidad (throttling) de RPC, memoria insuficiente, artefactos de Forge obsoletos, desviación de ABI o una configuración de fuente estática faltante. Ejecute `npm test` antes de diagnosticar un fallo de implementación.
