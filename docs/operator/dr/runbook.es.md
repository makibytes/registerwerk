---
title: Manual de recuperación ante desastres
description: Borrador de manual operativo para la restauración de Postgres y del backend, la verificación de la cadena de auditoría y la clasificación de incidentes DORA — pendiente de aprobación y pruebas por parte del operador.
---

# Manual de recuperación ante desastres { #disaster-recovery-runbook }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esto es un borrador de manual operativo, no evidencia de un plan de continuidad aprobado, de un RTO/RPO
    probado, de una clasificación de incidentes jurídicamente correcta ni de una notificación a las autoridades.
    El operador debe aprobarlo, ponerlo a prueba y conciliarlo con los requisitos legales, regulatorios,
    contractuales y de infraestructura vigentes.

**Servicio:** Registro eWpG Registerwerk  
**RTO:** medido 2.3 s en la base de datos casi vacía del simulacro (2026-10-07, `scripts/pitr-drill.sh`); no medido con datos del tamaño de producción, por lo que no se promete ningún RTO  
**RPO:** objetivo ≤ 15 minutos con el archivado de WAL activado (`archive_timeout` 300 s); el último simulacro (2026-10-07) midió 286.8 s entre la última fila confirmada y su segmento WAL archivado. Sin archivado de WAL (base de datos integrada de desarrollo/pruebas), el RPO es la última copia base diaria, hasta 24 horas  
**Responsable:** Equipo de Operaciones del Registro  
**Clasificación DORA:** véase la tabla ilustrativa de la sección 1

---

## 1. Clasificación de gravedad del incidente (DORA, art. 17) { #1-incident-severity-classification-dora-art-17 }

| Gravedad | Criterios | Acción |
|---|---|---|
| MINOR | Un solo servicio caído, sin pérdida de datos | Alerta interna |
| MAJOR | Caída de varios servicios, posible impacto en los datos | Evaluar la obligación de notificación según el art. 19 DORA |
| CRITICAL | Caída total O vulneración de la integridad de los datos | Evaluar la obligación de notificación según el art. 19 DORA |

Esta tabla es una clasificación ilustrativa, no una determinación jurídica. Verifíquela con el art. 19 DORA y con los actos
delegados sobre la clasificación de incidentes (Reglamento Delegado (UE) 2024/1772) y sobre el contenido y los plazos de las
notificaciones (Reglamento Delegado (UE) 2025/301). Los umbrales de duración y los plazos de notificación no se fijan aquí
a propósito: son política propia del operador (decisión aplazada T9-06), y los plazos legales corren desde los momentos que
fijan esos actos, no desde la «detección».

`POST /api/v1/dora/incidents` registra un incidente interno; no presenta un informe DORA. Toda
autoridad, plazo, formulario y canal indicados a continuación es un dato de referencia que debe
verificarse externamente:
- DE: BaFin (bafin.de), Referat IT-Risikoaufsicht
- LU: CSSF, a través del portal CSS
- FR: AMF / ACPR, a través de ONEGATE
- LI: FMA, a través del portal LIMA

---

## 2. Restauración completa de Postgres (PITR con WAL-G, RPO = último segmento WAL archivado)

!!! note "Probado de verdad"
    La sección 2a se ejercita con `scripts/pitr-drill.sh` en contenedores desechables (nunca en la base de datos de demostración): copia base, archivo WAL continuo, pérdida total del volumen de datos, restauración a una hora objetivo elegida y verificación fila a fila. Última ejecución 2026-10-07: PASSED, muestra de RPO 286.8 s, RTO 2.3 s (de ellos `wal-g backup-fetch` 1.5 s) en una base casi vacía. El RTO crece con el tamaño de la copia base y el WAL a reproducir; vuelva a medirlo con datos del tamaño de producción antes de comprometerse.

!!! warning "Solo con el archivado de WAL activado"
    La recuperación a un punto en el tiempo necesita un archivo WAL: el overlay opcional `docker-compose.wal.yml` (WAL-G), un PostgreSQL gestionado con PITR o CloudNativePG con Barman Cloud (véase [Copias de seguridad y recuperación](../maintenance/backups.md)). La base integrada de desarrollo/pruebas y la demo estándar de Compose no archivan nada; allí el punto de recuperación es la última copia base o el último pg_dump.

### 2a. Restauración a un punto en el tiempo desde WAL-G (ruta principal) { #2a-point-in-time-restore-from-wal-g-primary-path }

Mecánica de PostgreSQL 18: `PGDATA` es `/var/lib/postgresql/18/docker` en la imagen oficial (que declara `VOLUME /var/lib/postgresql`), la recuperación se solicita con un archivo `recovery.signal` vacío y el objetivo se fija en `postgresql.auto.conf`. Ya no existe `recovery.conf`.

```bash
# 0. Stop writers; keep the failed volume for forensics if it still exists
docker compose stop backend

# 1. Fresh, empty data volume; the archive volume (or the same S3 settings) is reused as is
docker volume create pgdata-restore

# 2. Fetch the base backup and write the recovery settings (what scripts/pitr-drill.sh does)
docker run --rm -i --user postgres -e RECOVERY_TARGET='2026-01-01 12:00:00+00' \
  -v pgdata-restore:/var/lib/postgresql -v <project>_pgarchive:/wal-archive \
  --entrypoint sh registerwerk/postgres-wal:18.6 -s <<'EOS'
set -eu
. /usr/local/bin/walg-env.sh                 # PGDATA + the WALG_* archive location
install -d -m 0700 "$PGDATA"
wal-g backup-fetch "$PGDATA" LATEST </dev/null
touch "$PGDATA/recovery.signal"
cat >> "$PGDATA/postgresql.auto.conf" <<CONF
restore_command = 'wal-g wal-fetch %f %p'
recovery_target_time = '${RECOVERY_TARGET}'   # UTC; omit the line to replay all archived WAL
recovery_target_action = 'promote'
CONF
EOS

# 3. Start Postgres on the restored volume and wait for recovery to finish
docker run -d --name postgres-restore -e POSTGRES_USER=registerwerk -e POSTGRES_DB=registerwerk \
  -e POSTGRES_PASSWORD=<password> -v pgdata-restore:/var/lib/postgresql \
  -v <project>_pgarchive:/wal-archive registerwerk/postgres-wal:18.6
docker logs -f postgres-restore 2>&1 | grep -E "recovery stopping|archive recovery complete|ready to accept"

# 4. Validate
psql -h localhost -U registerwerk -c "SELECT pg_is_in_recovery();"      # f once promoted
psql -h localhost -U registerwerk -c "SELECT max(occurred_at) FROM audit_event;"
```

Con almacenamiento S3, sustituya los montajes `-v ..._pgarchive` por las variables `WALG_S3_PREFIX` / `AWS_*` del servidor de origen. Tras la promoción, la instancia funciona en una nueva línea temporal: haga de inmediato una copia base nueva (`pg-backup once`). Para las formas de producción use el PITR de la plataforma: PostgreSQL gestionado (p. ej. `gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>`) o un clúster de recuperación de CloudNativePG (`deploy/helm/registerwerk/examples/cnpg-cluster.yaml`, al final). El script de este repositorio no ejercita esas dos vías; realice su propio simulacro de restauración.

Se pierden los datos confirmados después del objetivo de recuperación (o después del último segmento archivado). Compare `max(occurred_at)` con la hora del incidente para cuantificar la pérdida real y concilie después con los indexadores de cadena.

### 2b. Restaurar desde pg_dump (alternativa — RPO = último volcado) { #2b-restore-from-pgdump-fallback-rpo-last-dump }
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

**`scripts/dr-restore-drill.sh` automatiza solo esta vía alternativa de pg_dump (`scripts/pitr-drill.sh` cubre la vía WAL-G de 2a)** (pg_dump del servicio compose `postgres` en ejecución → restauración en un contenedor desechable → comparación del número de filas de cada tabla → informe de una cifra de RTO) y, con `--record-dora <backend-base-url> <bearer-token>`, registra el resultado como una entrada `SCENARIO_BASED` real en `POST /api/v1/dora/resilience-tests` — un simulacro de continuidad que un operador realmente ejecutó, no un marcador de posición sembrado por la demo. Deliberadamente no ejercita 2a, que tiene su propio simulacro (`scripts/pitr-drill.sh`, más abajo). Ejecútelo periódicamente (p. ej., cada trimestre) y tras cualquier cambio de esquema que toque migraciones, para mantener al día el «probada» de «continuidad probada».

Con `--verify-audit-chain`, además automatiza la mitad de la cadena hash de auditoría de esta sección: arranca un contenedor backend real y desechable contra la copia restaurada (HSM desactivado solo para ese contenedor desechable — no necesita maquinaria de wallet/firma, solo conectividad con la base de datos — de modo que la opción funciona con solo el servicio `postgres` en marcha, sin la pila de demostración completa) y llama al propio `POST /api/v1/audit/chain/verify` de la aplicación, en lugar de reimplementar en bash la canonicalización SHA-256 de `AuditChainVerificationService`, lo que correría el riesgo de divergir en silencio y dar una falsa confianza. Necesita `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` disponibles (entorno del shell o `.env` en la raíz del repositorio) y coincidentes con las credenciales con las que se sembró realmente la base de datos *de origen*; sin ellas, este paso se notifica como `SKIPPED`, no se trata como un fallo del simulacro.

**`scripts/pitr-drill.sh` ejercita la ruta WAL-G (2a).** Construye la misma imagen `postgres-wal` que el overlay de Compose, arranca un servidor desechable con `archive_mode=on`, hace una copia base, inserta filas antes y después de una hora objetivo registrada, espera sin forzar un cambio de segmento a que se archive el segmento de la última fila (la muestra de RPO), elimina el contenedor y su volumen, restaura copia base más WAL a la hora objetivo y comprueba que existen exactamente las filas confirmadas hasta entonces. Imprime el RPO y el RTO y dura unos 6 minutos con `archive_timeout` 300 s. Con `--record <backend-base-url> <operator-bearer-token>` registra el resultado como entrada `SCENARIO_BASED` mediante `POST /api/v1/dora/resilience-tests` (desactivado por defecto).

### 2c. Promoción de la réplica de lectura (Helm/Kubernetes, solo `values-production.yaml`)

`values-production.yaml` ejecuta opcionalmente una réplica de lectura de Postgres por streaming junto al primario (`postgresql.architecture: replication`) — un standby en caliente en tiempo real, no una copia de seguridad periódica. **No** es una conmutación automática: nada reapunta el `DB_URL` del backend a la réplica si el primario cae, y la réplica sigue siendo de solo lectura hasta que se promueve explícitamente. Si el primario se pierde y la réplica está intacta, esto es más rápido que una restauración completa con WAL-G (§2a):

```bash
# 1. Confirmar que el retraso de replicación de la réplica es lo bastante bajo para aceptar la pérdida de datos
kubectl exec -it <release-name>-postgresql-read-0 -- \
  psql -U postgres -c "SELECT now() - pg_last_xact_replay_timestamp() AS replication_lag;"

# 2. Promover la réplica fuera del modo de recuperación
kubectl exec -it <release-name>-postgresql-read-0 -- pg_ctl promote -D /bitnami/postgresql/data

# 3. Apuntar el backend a la instancia promovida
kubectl set env deployment/<release-name> \
  DB_URL="jdbc:postgresql://<release-name>-postgresql-read:5432/registerwerk"

# 4. Cuando el antiguo primario sea recuperable, reconstrúyalo como nueva réplica (NO deje que
#    vuelva a unirse como primario — él y la instancia promovida ya han divergido) o ejecute un nuevo
#    `helm upgrade` para que el subchart recree desde cero la topología primario/réplica.
```

Cualquier transacción que aún no se haya transmitido a la réplica en el momento de la promoción se pierde — es un RPO real, no cero; como en la vía WAL-G anterior, el RPO queda acotado por lo último capturado (aquí: por el retraso de replicación, no por el archivo). Practique esta promoción en un espacio de nombres que no sea de producción antes de confiar en ella durante un incidente real.

### 2d. Filas en una partición DEFAULT

`token_transfer`, `blockchain_transaction` y `audit_event` se particionan por mes. Una fila cuyo `occurred_at` queda fuera de las particiones existentes (fuente del indexador con reloj incorrecto, relleno histórico) acaba en `<tabla>_default`, y Postgres ya no puede crear la partición de ese mes. Salta la alerta `PartitionDefaultRowsPresent` (`registerwerk_partition_default_rows`). Divida la partición por defecto en una ventana de mantenimiento (tabla, mes y fechas son ejemplos):

```sql
BEGIN;
ALTER TABLE token_transfer DETACH PARTITION token_transfer_default;
CREATE TABLE token_transfer_2031_03 PARTITION OF token_transfer
  FOR VALUES FROM ('2031-03-01') TO ('2031-04-01');
INSERT INTO token_transfer_2031_03 SELECT * FROM token_transfer_default
  WHERE occurred_at >= '2031-03-01' AND occurred_at < '2031-04-01';
DELETE FROM token_transfer_default
  WHERE occurred_at >= '2031-03-01' AND occurred_at < '2031-04-01';
ALTER TABLE token_transfer ATTACH PARTITION token_transfer_default DEFAULT;
COMMIT;
```

Repita por cada mes y tabla afectados. No existe un job automático de división (requiere una decisión sobre la ventana de mantenimiento).

---

## 3. Restauración del backend { #3-backend-restore }
```bash
# Pull signed image (verify Cosign signature first)
cosign verify ghcr.io/makibytes/registerwerk/backend:VERSION

# Deploy with production environment
docker run -d \
  --env-file /etc/registerwerk/prod.env \
  -e REGISTERWERK_PRODUCTION_MODE=true \
  -p 127.0.0.1:48080:8080 \
  ghcr.io/makibytes/registerwerk/backend:VERSION

# Verify health
curl http://localhost:48080/actuator/health | jq .status
```

---

## 4. Verificación de la cadena de auditoría tras la restauración { #4-audit-chain-verification-after-restore }
```bash
# Trigger the verification: a POST (reading /actuator/health/auditChainVerificationService only
# reports the last verdict, it triggers nothing). Requires REGISTRY_ADMIN; scripts/dr-restore-drill.sh
# --verify-audit-chain shows the full login + XSRF-TOKEN flow.
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/api/v1/audit/chain/verify | jq .
```

Si el veredicto es BROKEN: NO reanude las operaciones. Escale como incidente CRITICAL; la ruptura de la cadena de hashes debe investigarse antes de que el registro reanude. Un veredicto BROKEN mantiene `/actuator/health` en DOWN (la disponibilidad no se ve afectada) hasta que una ejecución POSTERIOR sea válida Y el veredicto erróneo se reconozca con doble control: `POST /api/v1/audit/verification/{id}/ack` (REGISTRY_ADMIN, step-up, un segundo aprobador, motivo `AUDIT_CHAIN_VERIFICATION_ACK`, `note` opcional); la página del registro de auditoría del portal de operador tiene el botón correspondiente. El disparo es un POST; leer `/actuator/health/auditChainVerificationService` solo muestra el último veredicto.

---

## 5. Material clave de emergencia — break-glass (sustituye al endpoint exportRaw eliminado) { #5-key-material-break-glass-replaces-removed-exportraw-endpoint }

El acceso a la clave privada sin procesar requiere las tres condiciones siguientes:
1. Dos de cada tres fragmentos Shamir (en poder del CTO, el CFO y el asesor jurídico externo)
2. Resolución del consejo (aviso mínimo de 24 h al asesor jurídico regulatorio)
3. Registro de break-glass aprobado y auditado internamente. Cualquier notificación al regulador es
   específica del incidente, del operador y de la jurisdicción, y debe seguir el procedimiento
   aprobado externamente; este repositorio no la presenta ante ninguna autoridad.

Acceso de emergencia a KMS (AWS KMS):
```bash
aws kms decrypt \
  --ciphertext-blob fileb://wallet-wrapped-dek.bin \
  --key-id arn:aws:kms:eu-central-1:ACCT:key/KEY_ID \
  --output text --query Plaintext | base64 -d > dek.bin
```

---

## 6. Restauración de Kong / la puerta de enlace { #6-kong-gateway-restore }
Kong funciona sin base de datos (DB-less): toda su configuración es `gateway/kong.yml`, cargada al arrancar (`KONG_DECLARATIVE_CONFIG`). No hay base de datos que restaurar ni ruta de escritura por la API de administración, así que `deck sync` no aplica. Restaure el archivo desde el control de versiones, valídelo y recree el contenedor:
```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml
docker compose up -d --force-recreate kong
```
En Kubernetes el mismo archivo se entrega como `deploy/helm/registerwerk/files/kong.yml` (ConfigMap `registerwerk-kong-config`): ejecute `helm upgrade` y vuelva a desplegar Kong.

---

## 7. Lista de verificación posterior a la recuperación { #7-post-recovery-checklist }
- [ ] Estado de Postgres: `pg_isready`
- [ ] Estado del backend: `/actuator/health` → UP
- [ ] Cadena de auditoría: `POST /api/v1/audit/chain/verify` devuelve `valid: true` (sección 4); después `/actuator/health` → UP
- [ ] Actividad de los indexadores: `GET /api/v1/indexers` muestra todos los indexadores al día y no hay ninguna alerta `IndexerStaleCritical` / `IndexerStaleWarning` activa
- [ ] Deriva de cadena: confirmar que no hay filas `chain_drift_event` abiertas con severity=CRITICAL
- [ ] Filtrado de sanciones: confirmar que no hay filas `screening_hit` abiertas con más de 4 h de antigüedad
- [ ] Resumen del registro: verificar que los importes nominales totales coincidan con la instantánea previa al incidente
- [ ] Si el incidente se clasificó como grave, presentar las notificaciones DORA dentro de los plazos del acto delegado (verificar el texto vigente, sección 1)

## 8. Simulacros de caos (Docker Compose)

Dos scripts ejercitan un comportamiento real de fallo/recuperación contra la pila Compose en ejecución — no es una simulación — y, en el caso de `chaos-drill.sh`, registran el resultado como una fila real de DORA `ResilienceTest`, el mismo mecanismo que ya usa `dr-restore-drill.sh --record-dora`. Ambos reinician lo que detuvieron antes de salir, pero cuente con un breve reinicio del backend; no los ejecute contra una pila que otras personas estén usando activamente.

```bash
scripts/chaos-drill.sh kill-postgres   # SIGKILL a postgres en pleno tráfico; medir degradación + recuperación
scripts/chaos-drill.sh kill-backend    # SIGKILL al backend en plena petición; medir la recuperación
scripts/verify-graceful-shutdown.sh    # docker stop (SIGTERM) frente a lo anterior — el caso de contraste
```

**`kill-backend` encontró una brecha real y no documentada hasta entonces la primera vez que se ejecutó**: la política `restart: unless-stopped` de Docker **no** reinicia un contenedor tras `docker kill` o `docker stop` — confirmado porque `docker inspect ... RestartCount` se quedó en 0 tras la parada. Solo se recupera de un fallo genuino dentro del proceso que el propio runtime del contenedor observa, no de una terminación iniciada mediante la API del motor. `kill-backend` ahora lo mide con honestidad: espera 20 s a la recuperación automática y, si no ocurre, recurre a un `docker start` explícito y registra el resultado como `FINDINGS_OPEN`, no como `PASSED`. La vía Helm/Kubernetes no comparte esta brecha — `restartPolicy: Always` (el valor implícito de un Deployment) reinicia un pod tras *cualquier* salida del contenedor, administrativa o no. Si la autorreparación tras matar un contenedor importa específicamente para la vía Compose, eso es un seguimiento real (un supervisor externo, o aceptar la recuperación manual como modelo documentado), no algo que ninguno de los scripts disimule.

`verify-graceful-shutdown.sh` es el caso de contraste: lanza una ráfaga de peticiones concurrentes, envía un `docker stop` real (SIGTERM) a mitad de la ráfaga y confirma que el contenedor termina por sí solo dentro de su `stop_grace_period` (35 s, `docker-compose.yml` — alineado con `spring.lifecycle.timeout-per-shutdown-phase`, `application.yml`) en lugar de ser forzado por la escalada a SIGKILL de Docker, y que las peticiones en curso se completan en lugar de reiniciarse. Para eso sirve realmente `server.shutdown: graceful` — una parada/recreación normal, no un fallo — y es la razón por la que el servicio backend de `docker-compose.yml` necesita un `stop_grace_period` explícito: el tiempo de parada por defecto de Docker (10 s) es más corto que los 30 s que la aplicación usa para su propio vaciado.
