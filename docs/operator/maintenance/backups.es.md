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

### Implementación de Docker Compose: pg_dump { #docker-compose-deployment-pgdump }

Agregue este trabajo cron a su servidor (o ejecútelo como un contenedor Docker):

```bash
# /etc/cron.d/ewpg-backup
0 2 * * * root docker exec registerwerk-postgres-1 \
  pg_dump -U registerwerk registerwerk | gzip \
  > /backups/postgres/registerwerk-$(date +%Y%m%d-%H%M%S).sql.gz
```

Conserve copias de seguridad diarias durante 30 días, y copias de seguridad semanales durante 12 meses:

```bash
# Delete daily backups older than 30 days
find /backups/postgres -name "*.sql.gz" -mtime +30 -delete
```

### Implementación de Helm/Kubernetes: archivado continuo con WAL-G { #helmkubernetes-deployment-wal-g-continuous-archiving }

El enfoque de pg_dump de Docker Compose anterior **no** se aplica aquí. `deploy/helm/backup/` es un chart de Helm independiente (instalado como versión propia, junto al chart principal `deploy/helm/registerwerk` — no fusionado en él) que ejecuta un `CronJob` diario de WAL-G que archiva copias de seguridad continuas en S3. Antes de instalarlo, debe configurar dos valores del chart que este no puede derivar por sí solo:

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0
```

Consulte `deploy/helm/backup/values.yaml` para conocer el conjunto completo de opciones (bucket/región de S3, retención,
IRSA frente a credenciales estáticas de S3, URL opcional de Prometheus Pushgateway para la métrica de obsolescencia
que se describe más abajo).

Para cualquiera de las dos rutas, [pgBackRest](https://pgbackrest.org/) o [Barman](https://pgbarman.org/) siguen
siendo alternativas razonables si prefiere no ejecutar WAL-G directamente.

### Probar las copias de seguridad { #testing-backups }

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

### Restaurar desde una copia de seguridad de WAL-G (implementación de Helm/Kubernetes) { #restore-from-wal-g-backup-helmkubernetes-deployment }

```bash
# Scale the backend down to prevent writes during restore
kubectl scale deployment/registerwerk --replicas=0

# Restore the latest base backup + replay WAL to the target point in time
kubectl run wal-g-restore --rm -it --image=ghcr.io/wal-g/wal-g:v3.0.0 \
  --overrides='{"spec":{"volumes":[{"name":"pgdata","persistentVolumeClaim":{"claimName":"<postgresql-pvc-name>"}}]}}' \
  -- wal-g backup-fetch /bitnami/postgresql/data LATEST

# Scale the backend back up — Flyway will verify the schema
kubectl scale deployment/registerwerk --replicas=<original-replica-count>
```

### Objetivos de tiempo de recuperación { #recovery-time-objectives }

| Escenario | RTO (Objetivo de tiempo de recuperación) |
|----------|-------------------------------|
| Reinicio de un único servicio | < 1 minuto |
| Restauración de base de datos desde copia de seguridad diaria | < 30 minutos |
| Reconstrucción completa del servidor desde cero | < 4 horas |
| Estado del contrato inteligente | La restauración no está controlada por la copia de seguridad de la aplicación. Recupere las proyecciones de la aplicación por separado y concílielas con la cadena configurada y con el registro legal específico del instrumento; la blockchain no es universalmente autoritativa. |

## Monitorización de las copias de seguridad { #backup-monitoring }

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
