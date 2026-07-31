---
title: Actualización del Registro
---

# Actualización del Registro { #upgrading-the-registry }

Esta página cubre la actualización del backend, los frontends y los contratos inteligentes. Siga los procedimientos en orden: nunca actualice los contratos antes de actualizar el backend.

## Actualización del backend { #backend-upgrade }

### 1. Extraiga los últimos cambios { #1-pull-the-latest-changes }

```bash
git fetch origin
git pull origin main
git submodule update --recursive
```

### 2. Revise el registro de cambios { #2-review-the-changelog }

Compruebe `CHANGELOG.md` para ver cambios importantes, notas de migración de bases de datos y cambios de configuración antes de continuar.

### 3. Cree la nueva imagen de backend { #3-build-the-new-backend-image }

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

O extráigala del registro de contenedores:

```bash
docker pull ghcr.io/ewpg/registerwerk-backend:latest
```

### 4. Aplique la actualización { #4-apply-the-upgrade }

```bash
# Stop the backend gracefully (drains in-flight requests)
docker compose stop backend

# Start the new version — Flyway runs migrations automatically on startup
docker compose up -d backend

# Verify health
docker compose logs -f backend | grep -E "Started|ERROR"
curl http://localhost:8080/actuator/health
```

!!! warning
    Las migraciones de bases de datos se ejecutan automáticamente al inicio. Si falla una migración, el backend no se iniciará. Verifique los registros para detectar el error de migración específico. Nunca modifique manualmente la tabla del historial de Flyway.

### 5. Verificar { #5-verify }

Después del inicio:
- Compruebe la API en `http://localhost:8080/swagger-ui.html`
- Haga una llamada de prueba a la API contra un endpoint crítico
- Supervise el registro de auditoría para detectar errores inesperados durante los primeros 15 minutos

## Actualización de frontend { #frontend-upgrade }

```bash
# Operator frontend
cd frontend-operator
npm install
ng build --configuration production
docker compose up -d --build frontend-operator

# Customer frontend
cd ../frontend-customer
npm install
ng build --configuration production
docker compose up -d --build frontend-customer
```

Los frontends no tienen estado: las actualizaciones son de cero tiempo de inactividad.

## Actualizaciones de contratos inteligentes { #smart-contract-upgrades }

!!! warning
    Las actualizaciones de contratos inteligentes son las operaciones más sensibles. Todos los contratos pasan por una implementación y una auditoría en testnet antes de cualquier actualización en mainnet. Nunca actualice contratos de mainnet sin completar antes la validación en testnet.

### Contratos actualizables frente a no actualizables { #upgradeable-vs-non-upgradeable-contracts }

| Contrato | Actualizable | Ruta de actualización |
|----------|------------|-------------|
| `AssetTokenFactory` | No (fábrica CREATE2) | Implementar una nueva fábrica, actualizar la configuración del backend |
| `EwpgTREXFactory` | No | Implementar una nueva fábrica |
| `IdentityRegistryStorage` | Sí (proxy UUPS) | Actualizar la implementación del proxy |
| `ModularCompliance` | Sí (proxy UUPS) | Actualizar la implementación del proxy |
| Contratos de token (por emisión) | No | No se puede actualizar tras la implementación |

### Actualización de un contrato proxy UUPS { #upgrading-a-uups-proxy-contract }

```bash
cd contracts
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow
```

El script de actualización:
1. Implementa el nuevo contrato de implementación
2. Llama a `upgradeToAndCall` en el proxy UUPS
3. Verifica que la nueva implementación esté activa

### Actualización de los módulos de cumplimiento { #upgrading-compliance-modules }

Los módulos de cumplimiento se pueden añadir, eliminar o sustituir sin actualizar el propio contrato del token. Esta es la ruta de actualización preferida para cambios en la lógica de cumplimiento.

```bash
# Add a new compliance module to a token
curl -X POST http://localhost:8080/api/v1/admin/tokens/{tokenAddress}/compliance/modules \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -H "Content-Type: application/json" \
  -d '{"moduleAddress": "0xNewModuleAddress", "chain": "mainnet"}'
```

## Procedimiento de reversión { #rollback-procedure }

Si una actualización causa problemas, revierta volviendo a la etiqueta de imagen Docker anterior:

```bash
# Backend rollback
docker compose stop backend
docker tag ghcr.io/ewpg/registerwerk-backend:previous \
  registerwerk-backend:latest
docker compose up -d backend
```

Las migraciones de bases de datos no se pueden revertir automáticamente. Si es necesario revertir una migración, use los scripts de migración `DOWN` en `backend/src/main/resources/db/migration/` (presentes para todas las migraciones desde V10 en adelante).

## Actualización de Kong { #kong-upgrade }

```bash
docker compose stop kong
docker compose pull kong
docker compose up -d kong
```

Después de actualizar Kong, vuelva a aplicar la configuración declarativa:

```bash
deck sync --config gateway/kong.yml
```
sidebar_position: 3
---

# Actualizaciones { #upgrades }

## Actualización del backend { #backend-upgrade_1 }

1. Extraiga la nueva imagen o constrúyala localmente:
   ```bash
   docker build -t registerwerk-backend:v2.0.0 backend/
   ```

2. Actualice la etiqueta de imagen en `docker-compose.yml`

3. Inicie con un reinicio continuo (Flyway migra automáticamente):
   ```bash
   docker compose up -d --no-deps backend
   ```

4. Verifique el estado: `curl http://localhost:8080/actuator/health`

## Actualizaciones de contratos inteligentes { #smart-contract-upgrades_1 }

Los módulos de cumplimiento admiten la actualización in situ mediante `UpgradeCompliance.s.sol`:

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```

Los contratos de token e identidad **no son actualizables por diseño** (la inmutabilidad es un requisito legal para los valores). Las actualizaciones requieren implementar una nueva suite y migrar a los inversores.

## Actualizaciones de subgrafos { #subgraph-upgrades }

Si el esquema del subgrafo cambia, conserve la configuración anterior e implemente una versión nueva.
Antes de la implementación, verifique que cada dirección singleton tenga su propio `*_START_BLOCK_<SUFFIX>` real y que
cada entrada de BondDesk, AMM o RepoVault use `address@deploymentBlock`. Un bloque de fábrica no es un sustituto
válido de los bloques de implementación de las otras fuentes.

Renderice y compile todos los objetivos configurados sin publicar todavía:

```bash
SUBGRAPH_VALIDATE_ONLY=true ./indexer/evm/deploy-subgraph.sh all
```

Luego implemente con una etiqueta de versión que nunca se haya usado para los nombres de subgrafo afectados:

```bash
SUBGRAPH_VERSION_LABEL=schema-20260729-01 ./indexer/evm/deploy-subgraph.sh all
```

graph-node reindexa cada fuente renderizada desde el bloque configurado de esa fuente. Mantenga disponibles las versiones
anteriores y su configuración para una reversión no destructiva, hasta que cada reemplazo
haya alcanzado la cabeza de la cadena y sus rangos de eventos se hayan reconciliado de forma independiente. No elimine
el subgrafo anterior antes de la validación; revertir significa volver a implementar el manifiesto previamente aprobado
y la configuración de origen bajo otra etiqueta de versión nueva.

## Actualizaciones de Kong { #kong-upgrades }

1. Actualice la etiqueta de la imagen `kong` en `docker-compose.yml` (y en `gateway/docker-compose.kong.yml`
   si usa la pila independiente solo de puerta de enlace).
2. Reinicie Kong: `docker compose restart kong` — vuelve a leer `gateway/kong.yml` al iniciar
   (modo sin base de datos, sin migraciones que ejecutar).

## Actualizaciones de dependencias { #dependency-updates }

- **Java / Spring Boot**: actualice `pom.xml`, ejecute `mvn verify`
- **Angular**: `ng update @angular/core @angular/cli`
- **Contratos**: `forge update` (actualiza los submódulos de git en `contracts/lib/`)
