---
title: Solución de problemas
---

# Solución de problemas { #troubleshooting }

Esta página cubre los problemas más comunes al operar el Registro eWpG, junto con sus causas fundamentales y soluciones.

## Errores de blockchain / RPC { #blockchain-rpc-errors }

### "Blockchain RPC call failed" en los registros del backend { #blockchain-rpc-call-failed-in-backend-logs }

**Síntoma**: los registros del backend muestran `BlockchainException: RPC call failed for chain mainnet`, y las llamadas a la API devuelven HTTP 502.

**Causa**: no se puede acceder al endpoint RPC configurado, o este devuelve errores.

**Solución**:

1. Pruebe el endpoint RPC manualmente:

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

2. Si devuelve un error, cambie a un RPC de respaldo en `.env` y reinicie el backend
3. Consulte la página de estado de su proveedor de RPC para ver si hay incidentes en curso
4. Considere añadir una URL de RPC de respaldo en la configuración de la cadena

### La transacción de implementación del token nunca se confirma { #token-deployment-transaction-never-confirms }

**Síntoma**: tras hacer clic en "Deploy to Blockchain", el estado permanece en "Deploying" indefinidamente.

**Causa**: la transacción de implementación se envió pero nunca se confirmó (por ejemplo, precio de gas demasiado bajo, congestión de la red).

**Solución**:

1. Anote el hash de la transacción en la página de detalle de la emisión
2. Búsquelo en el explorador de bloques: ¿está pendiente o se ha descartado?
3. Si está pendiente, espere a que se despeje la congestión de la red, o use `cast` para acelerarla:

   ```bash
   cast send --gas-price 150gwei <tx-hash> --rpc-url $RPC_URL --private-key $DEPLOYER_KEY
   ```

4. Si se ha descartado, el backend reintentará automáticamente cada 5 minutos (hasta 3 veces)
5. Si todos los reintentos fallan, la emisión vuelve al estado APPROVED: haga clic en Deploy de nuevo

---

## Brechas en el indexador { #indexer-gaps }

### El subgrafo no se sincroniza { #subgraph-is-not-syncing }

**Síntoma**: el panel muestra la cadena como "DEGRADED" o "CRITICAL". Los registros de graph-node muestran que la indexación se ha detenido.

**Solución**:

1. Consulte los registros de graph-node:

   ```bash
   docker compose logs --tail=50 graph-node | grep -i "error\|failed\|panic"
   ```

2. Compruebe el estado del RPC — a menudo se debe a que el proveedor de RPC limita la velocidad (rate-limiting) de graph-node
3. Añada un segundo proveedor de RPC como respaldo en `graph-node.toml`
4. Reinicie graph-node:

   ```bash
   docker compose restart graph-node
   ```

5. Si el subgrafo está atascado con un error fatal, vuelva a implementarlo (véase [The Graph](./indexers/the-graph.md))

### Faltan eventos de transferencia en el registro { #missing-transfer-events-in-registry }

**Síntoma**: una transferencia visible en el explorador de bloques no aparece en el registro.

**Causa**: el indexador estaba por detrás de la cabeza de la cadena en el momento de la transferencia, o el subgrafo se volvió a implementar desde un bloque de inicio posterior a la transferencia.

**Solución**:

1. Compruebe el estado actual del indexador:

   ```bash
   curl http://localhost:8080/api/v1/admin/chains \
     -H "Authorization: Bearer $OPERATOR_JWT" \
     | jq '.[].latestIndexedBlock'
   ```

2. Si el indexador se ha puesto al día y el evento sigue faltando, realice una comparación controlada de forma independiente
   entre los eventos del subgrafo y `eth_getLogs` para el rango afectado. El
   endpoint de administración `verify-consistency` planificado no está implementado.

3. Si se confirma una brecha, vuelva a implementar el subgrafo desde un bloque anterior al evento faltante

---

## Errores de carga de KYC { #kyc-upload-errors }

### Error "Document too large" { #document-too-large-error }

**Síntoma**: la carga del documento KYC falla con "file size exceeds limit".

**Causa**: el límite de tamaño de documento predeterminado es 20 MB (complemento `request-size-limiting` de Kong).

**Solución**: comprima el documento antes de cargarlo. Si el documento original supera los 20 MB, pida al cliente que proporcione una versión comprimida. Como operador, puede aumentar el límite en `gateway/kong.yml`:

```yaml
plugins:
  - name: request-size-limiting
    config:
      allowed_payload_size: 50  # MB
```

### "S3 upload failed" en los registros del backend { #s3-upload-failed-in-backend-logs }

**Síntoma**: los registros del backend muestran `S3UploadException` al guardar un documento KYC.

**Causa**: las credenciales de S3 son incorrectas, el bucket no existe, o la política de IAM no permite `PutObject`.

**Solución**:

1. Verifique las credenciales de S3 en `.env`
2. Pruebe el acceso a S3:

   ```bash
   aws s3 ls s3://your-kyc-bucket
   ```

3. Asegúrese de que la política de IAM incluya `s3:PutObject`, `s3:GetObject` y `s3:DeleteObject` para el bucket

---

## Errores de autenticación { #authentication-errors }

### "JWT validation failed" — respuestas 401 { #jwt-validation-failed-401-responses }

**Síntoma**: las peticiones a la API devuelven 401 con `JWT validation failed` aunque el token parezca válido.

**Causa**: el emisor del token no coincide con `JWT_ISSUER_URI`, o no se puede acceder al endpoint JWKS.

**Solución**:

1. Decodifique el JWT en [jwt.io](https://jwt.io) y verifique que el claim `iss` coincida con su `JWT_ISSUER_URI`
2. Compruebe que el backend puede alcanzar el endpoint JWKS:

   ```bash
   docker exec registerwerk-backend-1 \
     curl ${JWT_ISSUER_URI}/.well-known/jwks.json
   ```

3. Si no se puede acceder al endpoint JWKS desde dentro de la red de Docker, configure `JWT_JWKS_URI` explícitamente

### Los usuarios no pueden iniciar sesión tras un cambio de IdP { #users-cannot-log-in-after-idp-change }

**Síntoma**: tras cambiar una entidad cliente a un IdP personalizado, sus usuarios reciben "Access denied".

**Solución**:

1. Verifique que la redirect URI del IdP del cliente esté configurada correctamente
2. Pruebe la integración del IdP mediante **Entities → [entidad] → Identity Provider → Test**
3. Revise los registros del backend para ver el error OIDC concreto (normalmente `redirect_uri_mismatch` o `invalid_client`)

---

## Problemas de base de datos { #database-issues }

### El backend no arranca — error de migración de Flyway { #backend-fails-to-start-flyway-migration-error }

**Síntoma**: el contenedor del backend se cierra al arrancar con `FlywayException: Validate failed`.

**Causa**: se modificó un archivo de migración después de haberse aplicado, o las migraciones están desordenadas.

**Solución**:

```bash
# Check current migration state
docker exec registerwerk-postgres-1 \
  psql -U ewpg -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

Si una migración muestra `success = false`, corrija el SQL de la migración y reinicie. Nunca modifique archivos de migración que ya se hayan aplicado en producción.

### Disco lleno en el volumen de PostgreSQL { #disk-full-on-postgresql-volume }

**Síntoma**: el backend devuelve errores 500. Los registros de Postgres muestran `FATAL: could not write to file`.

**Solución**:

1. Identifique las tablas más grandes:

   ```sql
   SELECT relname, pg_size_pretty(pg_total_relation_size(relid))
   FROM pg_catalog.pg_statio_user_tables
   ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
   ```

2. La tabla `audit_log` está particionada por rangos mensuales. Elimine particiones antiguas si el disco está en estado crítico:

   ```sql
   DROP TABLE audit_log_y2024m01;
   ```

3. Amplíe el volumen de Docker y reinicie PostgreSQL

# Solución de problemas { #troubleshooting_1 }

## El backend no arranca { #backend-wont-start }

**Síntoma**: `Connection refused` en `localhost:8080`

1. Verifique la conectividad con la base de datos: `docker compose logs postgres`
2. Verifique las migraciones de Flyway: busque `FlywayException` en los registros del backend
3. Verifique que las variables de entorno necesarias estén configuradas (especialmente `DB_PASSWORD`, `JWT_ISSUER_URI`)

## Los tokens no aparecen en el historial { #tokens-not-appearing-in-history }

**Síntoma**: `GET /api/v1/assets/{id}/history` devuelve vacío

1. Verifique que graph-node esté en ejecución: `curl http://localhost:8020/health`
2. Verifique que el subgrafo esté implementado: `curl http://localhost:8000/subgraphs/name/ewpg/ethereum-sepolia`
3. Verifique el estado del indexador: `SELECT * FROM indexer_state;`
4. Verifique que `graphNodeUrl` y `graphSubgraphName` estén configurados en la configuración de la cadena

## La implementación de ERC-3643 falla { #erc-3643-deployment-fails }

**Síntoma**: `POST /api/v1/assets/{id}/deployments` devuelve 500

1. Verifique que el monedero del implementador tenga ETH para el gas
2. Verifique que `REGISTRY_WALLET_PRIVATE_KEY` esté configurado
3. Verifique que el submódulo de T-REX esté inicializado: `ls contracts/lib/erc3643/`
4. Busque el error de `Web3j` en los registros del backend

## Kong devuelve 401 Unauthorized { #kong-returns-401-unauthorized }

Kong no valida los JWT — un 401 siempre proviene del **backend**, incluso en solicitudes
enrutadas a través de Kong. Verifique, en orden:

1. Que `JWT_ISSUER_URI` coincide con el `iss` que su proveedor OIDC realmente devuelve
2. Que `JWT_AUDIENCE` coincide con el `aud` del token — un desajuste aquí es la causa más frecuente
3. Que, para un token de operador, ese `iss` es `registerwerk-local`; los tokens locales sin él son
   rechazados por diseño, así que un token hecho a mano al que le falte `iss` siempre dará 401
4. Decodifique el token para inspeccionar sus claims

Si el token es válido y obtiene **403**, el token está bien y el *rol* no lo está —
un problema completamente distinto. Véase [Roles y permisos](customers/roles.md).

## El token de incorporación ha caducado { #onboarding-token-expired }

Regenérelo a través de la API:
```bash
curl -X POST http://localhost:8000/api/v1/onboarding/tokens \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -d '{"legalEntityId": "<uuid>", "recipientEmail": "admin@acme.de"}'
```

Los tokens antiguos se invalidan automáticamente (índice único parcial `WHERE used_at IS NULL`).

## La transferencia de token confidencial falla en Fhenix { #confidential-token-transfer-fails-on-fhenix }

1. Asegúrese de que el cliente esté usando `fhevmjs` para cifrar el importe
2. Verifique que el ONCHAINID del inversor tenga una atestación KYC válida
3. Verifique que la dirección del inversor esté incluida en la lista blanca del IdentityRegistry del token
4. La testnet de Fhenix puede tener cuentas limitadas por grifo (faucet) — verifique el saldo de gas
