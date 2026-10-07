---
title: Copias de seguridad y recuperación
---

# Copias de seguridad y recuperación { #backups-and-recovery }

El estado persistente del registro se encuentra en dos lugares:
1. **PostgreSQL**: todos los datos de registro (emisiones, entidades, registro de auditoría, KYC, estado del indexador)
2. **S3/almacenamiento de objetos**: documentos KYC de más de 5 MB

El estado del contrato inteligente se encuentra en la blockchain y se replica de forma inherente; no es necesario hacerle una copia de seguridad por separado.

## Estrategia de copia de seguridad de PostgreSQL { #postgresql-backup-strategy }

Las dos rutas de implementación documentadas en el `CLAUDE.md` del repositorio utilizan dos mecanismos de copia de seguridad diferentes y **no intercambiables**. Siga la sección que coincida con cómo está ejecutando realmente Registerwerk — anteriormente, esta página solo describía la ruta de Docker Compose, lo que induciría a error a un operador que ejecuta la implementación de Helm/Kubernetes (Fase 12, hallazgo n.° 6).

### Implementación de Docker Compose — pg_dump manual

Esta es la variante de demostración local/de un solo host (véase la advertencia «Not a production topology» de `CLAUDE.md`), no una simulación de una estrategia real de copias de seguridad: `docker compose up` no lanza ningún servicio de copia automática. Los despliegues reales ejecutan PostgreSQL como servicio gestionado (Cloud SQL en GKE) con su propia copia/PITR; véanse las formas de producción más abajo. Volcado manual de la base de datos de demostración local:

```bash
docker compose exec postgres pg_dump -U ${DB_USER:-registerwerk} --no-owner --no-privileges registerwerk \
  | gzip > registerwerk-$(date -u +%Y%m%d-%H%M%S).sql.gz
```

Este archivo local no tiene retención programada ni replicación externa: es una ayuda manual puntual, no una estrategia de copias de seguridad.

### Implementación de Docker Compose — archivado de WAL y PITR opcionales

La pila por defecto anterior no cambia. El overlay `docker-compose.wal.yml` es una activación explícita que añade archivado continuo de WAL y recuperación a un punto en el tiempo con [WAL-G](https://github.com/wal-g/wal-g):

```bash
# postgres with archive_mode=on (recreates the container; the pg18data volume is reused as is)
docker compose -f docker-compose.yml -f docker-compose.wal.yml up -d postgres
# daily base backups + retention (profile "backup"); one-shot variant: ... run --rm pg-backup once
docker compose -f docker-compose.yml -f docker-compose.wal.yml --profile backup up -d pg-backup
```

El overlay sustituye la imagen por `registerwerk/postgres-wal:18.6` (`postgres-wal/`: la misma base `postgres:18.6-alpine` más WAL-G compilado desde una etiqueta fija, por lo que un volumen existente no necesita volcado/restauración) y establece `archive_mode=on`, `archive_command='wal-g wal-push %p'` y `archive_timeout`. Ajustes (entorno o `.env`):

| Variable | Por defecto | Significado |
|---|---|---|
| `PG_ARCHIVE_TIMEOUT` | `300` | Segundos tras los cuales un servidor activo debe cambiar de segmento WAL y archivarlo. Es el mando del RPO: el RPO queda acotado por este valor más el tiempo de subida |
| `WALG_FILE_PREFIX` | `/wal-archive` | Archivo local (volumen `pgarchive`). Mismo host que la base de datos: protege frente a pérdida lógica y corrupción, **no** frente a la pérdida de la máquina |
| `WALG_S3_PREFIX` + `AWS_ENDPOINT`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_FORCE_PATH_STYLE` | vacío | Archivo compatible con S3 (prevalece sobre el prefijo de archivo); el bucket debe existir ya. El servicio `minio` del perfil `audit-anchor` sirve para una demo local |
| `BACKUP_INTERVAL_SECONDS` / `BACKUP_RETAIN_FULL` | `86400` / `7` | Cadencia de las copias base y número de copias completas conservadas |
| `BACKUP_PUSHGATEWAY_URL` | vacío | Recibe `backup_last_success_timestamp` (la métrica de `BackupStale`) tras cada copia |

Restauración y valores medidos: [runbook de DR, sección 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path). El modo S3 se configura con las mismas variables, pero el simulacro no lo ejercitó (usó el archivo local).

### Implementación de Helm/Kubernetes — tres formas de base de datos

El chart principal declara en qué forma se apoya una release (`database.shape` en `deploy/helm/registerwerk/values.yaml`; el chart rechaza combinaciones que contradigan `postgresql.enabled`, y `NOTES.txt` indica qué cubre y qué no la forma). Objetivo de las dos formas de producción: **RPO ≤ 15 minutos** mediante archivado continuo de WAL (`archive_timeout` 300 s).

| `database.shape` | PostgreSQL | Punto de recuperación | Uso |
|---|---|---|---|
| `managed` | Servicio gestionado con PITR (Cloud SQL mediante `cloudSqlProxy`, RDS, Azure Flexible Server, ...); `postgresql.enabled=false` | PITR del proveedor (archivo continuo de registros) | Producción |
| `cnpg` | Clúster CloudNativePG con archivado WAL de Barman Cloud en almacenamiento de objetos, operado fuera de este chart; `postgresql.enabled=false` | Archivo WAL continuo (`archive_timeout` 300 s) | Producción |
| `bundled` (por defecto) | Subchart de Bitnami, primario único | Última copia base diaria (hasta 24 h), sin PITR | Solo desarrollo/pruebas |

#### PostgreSQL gestionado con PITR (`database.shape: managed`)

Active estos puntos en el proveedor; el chart no puede verificarlos, trátelos como lista de aceptación:

- Recuperación a un punto en el tiempo / archivado continuo de registros de transacciones activado, con al menos 7 días de retención de registros (Cloud SQL: `--enable-point-in-time-recovery --retained-transaction-log-days=7`; RDS: retención de copias automáticas de al menos 7 días; Azure Flexible Server: retención PITR de 7 a 35 días).
- Copias diarias automáticas con suficientes copias conservadas para su política de retención, alta disponibilidad (regional/multi-AZ) y protección contra eliminación activadas.
- No configure usted `archive_mode`/`archive_command`; los gestiona el proveedor. El RPO es el intervalo de envío de registros que documente el proveedor (confirme la cifra en el contrato/SLA en el que se apoye).
- Genere alertas con la métrica propia de salud de archivado/PITR del proveedor y ensaye un clon real a un punto en el tiempo en una instancia de prueba (`gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>` en Cloud SQL) antes de necesitarlo. `scripts/pitr-drill.sh` no maneja un servicio gestionado.
- Aprovisione una sola vez el login de ejecución solo-DML con `postgres-init/roles/ensure-runtime-role.sh`.

#### CloudNativePG con Barman Cloud (`database.shape: cnpg`)

`deploy/helm/registerwerk/examples/cnpg-cluster.yaml` es un ejemplo completo: `ObjectStore` (destino S3), un `Cluster` de 3 instancias con `archive_timeout: "300"`, el plugin Barman Cloud como archivador de WAL, un volumen WAL separado, `enablePodMonitor` (expone las métricas `cnpg_pg_stat_archiver_*` que lee la alerta `WalArchiveLag`), un `ScheduledBackup` diario y un `Cluster` de recuperación comentado con `recoveryTarget.targetTime`. Instale primero el operador y el plugin, aplique después el manifiesto, establezca `database.shape=cnpg` y `postgresql.enabled=false` y apunte `env.DB_URL` al servicio `-rw`. El manifiesto no se ha aplicado a un clúster real en este repositorio; valídelo con las versiones de su operador y plugin.

#### PostgreSQL integrado (`database.shape: bundled`, desarrollo/pruebas)

`deploy/helm/backup/` es un chart de Helm independiente (instalado como su propia release, junto al chart principal y no fusionado con él) que ejecuta un `CronJob` diario de WAL-G que envía una **copia base** de la base de datos integrada a S3.

!!! warning "El RPO es la última copia base diaria"
    La base de datos integrada no archiva WAL, así que el punto de recuperación es la última copia base correcta (hasta 24 horas) y la recuperación a un punto en el tiempo no está disponible. Un `wal-g backup-push` fallido hace fallar el job, igual que un envío fallido a Pushgateway, de modo que se dispara `BackupStale`. Para el objetivo de 15 minutos use `managed` o `cnpg`.

Antes de instalarlo, establezca tres valores del chart que no puede deducir:

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0 \
  --set postgresql.existingSecret=<main chart's postgresql.auth.existingSecret>
```

Consulte `deploy/helm/backup/values.yaml` para todas las opciones (bucket/región de S3, retención, IRSA frente a credenciales S3 estáticas, URL opcional de Pushgateway). [pgBackRest](https://pgbackrest.org/) sigue siendo una alternativa razonable a WAL-G.

### Probar las copias de seguridad { #testing-backups }

**Simulacro PITR (`scripts/pitr-drill.sh`).** Ejecuta todo el camino WAL-G en contenedores y volúmenes desechables (nunca en la base de datos de demostración): copia base, archivo WAL, pérdida total del volumen de datos, restauración a una hora objetivo registrada y verificación fila a fila. Imprime la muestra de RPO medida y el RTO, y tarda unos 6 minutos con el `archive_timeout` por defecto. `--record <backend-base-url> <operator-bearer-token>` registra el resultado como entrada `SCENARIO_BASED` mediante `POST /api/v1/dora/resilience-tests` (desactivado por defecto). Última ejecución 2026-10-07: **PASSED**, muestra de RPO **286.8 s** (límite: `archive_timeout` 300 s más tiempo de subida, objetivo ≤ 15 min), RTO **2.3 s** (de ellos `wal-g backup-fetch` 1.5 s) en una base casi vacía. El RTO no es una promesa para producción: crece con el tamaño de la copia base y el WAL a reproducir; repita el simulacro (y cronometre la restauración) con datos del tamaño de producción.

```bash
scripts/pitr-drill.sh
scripts/pitr-drill.sh --record http://localhost:48080 "$OPERATOR_TOKEN"
```

Pruebe su procedimiento de copia de seguridad y restauración al menos una vez al mes:

```bash
# Restore a backup to a test database
gunzip -c /backups/postgres/registerwerk-20250401-020000.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U registerwerk registerwerk_test
```

Verifique que las tablas clave estén presentes y que los recuentos de datos sean razonables:

```sql
SELECT 'entities' AS tbl, COUNT(*) FROM entity
UNION ALL
SELECT 'assets', COUNT(*) FROM asset
UNION ALL
SELECT 'deployments', COUNT(*) FROM asset_deployment
UNION ALL
SELECT 'transfers', COUNT(*) FROM token_transfer
UNION ALL
SELECT 'audit_log', COUNT(*) FROM audit_log;
```

## Copia de seguridad de documentos en S3 { #s3-document-backup }

Habilite el control de versiones de S3 y la replicación entre regiones para su bucket de documentos KYC:

```bash
# Enable versioning
aws s3api put-bucket-versioning \
  --bucket your-kyc-bucket \
  --versioning-configuration Status=Enabled

# Enable cross-region replication (requires destination bucket in another region)
aws s3api put-bucket-replication \
  --bucket your-kyc-bucket \
  --replication-configuration file://replication.json
```

## Recuperación ante desastres { #disaster-recovery }

### Restauración completa desde la copia de seguridad de pg_dump (implementación de Docker Compose) { #full-restore-from-pgdump-backup-docker-compose-deployment }

```bash
# Stop the backend to prevent writes during restore
docker compose stop backend

# Drop and recreate the database
docker exec registerwerk-postgres-1 \
  psql -U registerwerk -c "DROP DATABASE registerwerk; CREATE DATABASE registerwerk;"

# Restore
gunzip -c /backups/postgres/registerwerk-latest.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U registerwerk registerwerk

# Restart the backend — Flyway will verify the schema
docker compose start backend
```

### Restauración a un punto en el tiempo (WAL-G, CloudNativePG, gestionado)

El procedimiento PITR paso a paso (PostgreSQL 18: `recovery.signal` y `recovery_target_time` en `postgresql.auto.conf`, sin `recovery.conf`) está en el [runbook de DR, sección 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path). Para CloudNativePG use el `Cluster` de recuperación al final de `examples/cnpg-cluster.yaml`; para un servicio gestionado, el clon a un punto en el tiempo del proveedor. La base integrada de desarrollo/pruebas solo puede restaurarse a su última copia base (`wal-g backup-fetch /bitnami/postgresql/data LATEST` desde un pod con el PVC de datos montado, escalando antes el backend a cero).

### Objetivos de recuperación

| Elemento | Valor | Base |
|---|---|---|
| RPO, archivado de WAL activo | ≤ 5 min con actividad de escritura (`archive_timeout` 300 s + subida); objetivo ≤ 15 min | Simulacro 2026-10-07: 286.8 s medidos |
| RPO, base integrada de desarrollo/pruebas | Hasta 24 h (última copia base diaria) | Por construcción, sin archivo WAL |
| RTO, restauración PITR | 2.3 s en la base casi vacía del simulacro | Simulacro 2026-10-07; no medido con datos del tamaño de producción |
| RTO, reconstrucción completa del servidor | No medido | Sin simulacro; no se promete ninguna cifra |
| Estado de los contratos inteligentes | La restauración no la controla la copia de seguridad de la aplicación. Recupere las proyecciones de la aplicación por separado y concílielas con la cadena configurada y el registro legal propio del instrumento; la blockchain no es universalmente la fuente de verdad. |  |

## Monitorización de las copias de seguridad { #backup-monitoring }

`BackupStale` salta ahora si la última copia tiene más de 24 h **o** la serie no existe; `BackupMetricAbsent` (serie ausente 2 días) y `BackupPushStale` lo complementan. Sin servicio de copias (Docker Compose), `BackupStale` salta porque la serie no existe.

`monitoring/alerts/registerwerk.yml` ya incluye una regla `BackupStale` que consulta
`backup_last_success_timestamp`. Esa métrica solo existe si algo realmente la envía — un
CronJob es efímero y no se puede scrapear directamente, por lo que ambas rutas de copia de seguridad la envían a un Prometheus
Pushgateway cuando tienen éxito:

- **Helm/Kubernetes (WAL-G)**: configure `monitoring.pushgatewayUrl` en `deploy/helm/backup/values.yaml`
  — el CronJob la envía automáticamente una vez configurado (consulte `templates/backup-cronjob.yaml`).
- **Docker Compose (pg_dump)**: añada el envío equivalente al final de su script de cron:

  ```bash
  curl -s -X POST --data-binary "backup_last_success_timestamp $(date +%s)" \
    "$PUSHGATEWAY_URL/metrics/job/registerwerk_pg_backup"
  ```

Ninguna de las dos rutas incluye un Pushgateway por defecto — añada uno a la pila de monitorización que esté
usando (`monitoring/docker-compose.yml` para Compose, o uno para todo el clúster en el caso de Kubernetes) si
quiere que esta alerta tenga datos reales detrás.

El archivado de WAL tiene alertas propias en el mismo archivo: `WalArchiveLag` (ningún segmento WAL archivado en más de 15 minutos, durante 5 minutos), `WalArchiveFailing` (`pg_stat_archiver.failed_count` crece) y `WalArchiveMetricAbsent` (no se recoge ninguna métrica de origen, así que la alerta de retraso nunca podría dispararse). Leen una de dos fuentes:

- `cnpg_pg_stat_archiver_seconds_since_last_archival` y `cnpg_pg_stat_archiver_failed_count` del monitoreo integrado de CloudNativePG (`enablePodMonitor`; compruebe los nombres con su versión de CNPG).
- `pg_wal_archive_last_archive_age_seconds` y `pg_wal_archive_failed_count` de una consulta personalizada sobre `pg_stat_archiver` en su exporter de PostgreSQL (p. ej. el archivo de consultas de `postgres_exporter`; confirme que su versión aún admite consultas personalizadas):

```yaml
pg_wal_archive:
  query: |
    SELECT failed_count,
           COALESCE(EXTRACT(EPOCH FROM (now() - last_archived_time)), 1e9) AS last_archive_age_seconds
    FROM pg_stat_archiver
  master: true
  metrics:
    - failed_count:
        usage: COUNTER
        description: Failed archive_command attempts
    - last_archive_age_seconds:
        usage: GAUGE
        description: Seconds since the last WAL segment was archived
```

El registro siempre tiene actividad de escritura, así que con `archive_timeout` la antigüedad del archivo se mantiene por debajo del tiempo límite; una antigüedad superior a 15 minutos significa que el archivado está bloqueado, no que la base de datos esté inactiva. Los servicios gestionados exponen su propio equivalente: conéctelo a la misma alerta o genere alertas con la métrica del proveedor. `monitoring/alerts/tests/registerwerk.test.yml` prueba estas reglas con tests unitarios (`promtool test rules`).
