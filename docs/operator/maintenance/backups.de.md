---
title: Backups und Wiederherstellung
---

# Backups und Wiederherstellung

Der dauerhafte Zustand des Registers befindet sich an zwei Orten:
1. **PostgreSQL** – alle Registerdaten (Emissionen, Entitäten, Audit-Log, KYC, Indexer-Status)
2. **S3 / Objektspeicher** – KYC-Dokumente größer als 5 MB

Der Smart-Contract-Zustand liegt auf der Blockchain und ist von Natur aus repliziert – er muss nicht separat gesichert werden.

## PostgreSQL-Backup-Strategie

Die beiden in der `CLAUDE.md` des Repositorys dokumentierten Bereitstellungspfade verwenden zwei unterschiedliche, **nicht
austauschbare** Backup-Mechanismen. Folgen Sie dem Abschnitt, der zu Ihrer tatsächlichen Registerwerk-Bereitstellung passt —
bisher beschrieb diese Seite nur den Docker-Compose-Pfad, was einen Betreiber, der die Helm-/Kubernetes-Bereitstellung
fährt, in die Irre führen würde (Phase 12, Befund Nr. 6).

### Docker-Compose-Bereitstellung – pg_dump

Fügen Sie diesen Cron-Job auf Ihrem Server hinzu (oder führen Sie ihn als Docker-Container aus):

```bash
# /etc/cron.d/ewpg-backup
0 2 * * * root docker exec registerwerk-postgres-1 \
  pg_dump -U registerwerk registerwerk | gzip \
  > /backups/postgres/registerwerk-$(date +%Y%m%d-%H%M%S).sql.gz
```

Bewahren Sie tägliche Backups 30 Tage lang und wöchentliche Backups 12 Monate lang auf:

```bash
# Delete daily backups older than 30 days
find /backups/postgres -name "*.sql.gz" -mtime +30 -delete
```

### Helm-/Kubernetes-Bereitstellung – kontinuierliche WAL-G-Archivierung

Der obige Docker-Compose-pg_dump-Ansatz gilt hier **nicht**. `deploy/helm/backup/` ist ein
separates Helm-Chart (installiert als eigenes Release, neben dem Haupt-Chart
`deploy/helm/registerwerk` – nicht darin zusammengeführt), das einen täglichen WAL-G-`CronJob` betreibt, der
kontinuierlich Backups nach S3 archiviert. Vor der Installation müssen Sie zwei Chart-Werte setzen, die es nicht
selbst ableiten kann:

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0
```

Den vollständigen Satz an Optionen (S3-Bucket/Region, Aufbewahrung, IRSA vs. statische
S3-Zugangsdaten, optionale Prometheus-Pushgateway-URL für die unten beschriebene Staleness-Metrik) finden Sie in
`deploy/helm/backup/values.yaml`.

Für beide Pfade bleiben [pgBackRest](https://pgbackrest.org/) oder [Barman](https://pgbarman.org/) sinnvolle
Alternativen, falls Sie WAL-G nicht direkt betreiben möchten.

### Backups testen

Testen Sie Ihr Backup- und Wiederherstellungsverfahren mindestens monatlich:

```bash
# Restore a backup to a test database
gunzip -c /backups/postgres/registerwerk-20250401-020000.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U registerwerk registerwerk_test
```

Überprüfen Sie, ob die wichtigsten Tabellen vorhanden sind und die Datensatzzahlen plausibel sind:

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

## S3-Dokumentensicherung

Aktivieren Sie S3-Versionierung und regionsübergreifende Replikation für Ihren KYC-Dokumenten-Bucket:

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

## Notfallwiederherstellung

### Vollständige Wiederherstellung aus einem pg_dump-Backup (Docker-Compose-Bereitstellung)

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

### Wiederherstellung aus einem WAL-G-Backup (Helm-/Kubernetes-Bereitstellung)

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

### Wiederherstellungszeitziele

| Szenario | RTO (Recovery Time Objective) |
|----------|-------------------------------|
| Neustart eines einzelnen Dienstes | < 1 Minute |
| Datenbankwiederherstellung aus täglichem Backup | < 30 Minuten |
| Vollständiger Serverneuaufbau von Grund auf | < 4 Stunden |
| Smart-Contract-Zustand | Die Wiederherstellung wird nicht durch das Anwendungs-Backup gesteuert. Anwendungsprojektionen separat wiederherstellen und mit der konfigurierten Chain sowie dem instrumentenspezifischen Rechtsregister abgleichen; die Blockchain ist nicht universell maßgeblich. |

## Backup-Überwachung

`monitoring/alerts/registerwerk.yml` enthält bereits eine `BackupStale`-Regel, die
`backup_last_success_timestamp` abfragt. Diese Metrik existiert nur, wenn etwas sie tatsächlich pusht – ein
CronJob ist kurzlebig und kann nicht direkt gescrapt werden, daher pushen beide Backup-Pfade sie bei Erfolg an ein
Prometheus-Pushgateway:

- **Helm/Kubernetes (WAL-G)**: `monitoring.pushgatewayUrl` in `deploy/helm/backup/values.yaml` setzen
  – der CronJob pusht automatisch, sobald das gesetzt ist (siehe `templates/backup-cronjob.yaml`).
- **Docker Compose (pg_dump)**: Fügen Sie den entsprechenden Push ans Ende Ihres Cron-Skripts an:

  ```bash
  curl -s -X POST --data-binary "backup_last_success_timestamp $(date +%s)" \
    "$PUSHGATEWAY_URL/metrics/job/registerwerk_pg_backup"
  ```

Keiner der beiden Pfade liefert standardmäßig ein Pushgateway – fügen Sie eines zu dem Monitoring-Stack hinzu, den Sie
betreiben (`monitoring/docker-compose.yml` für Compose, oder ein clusterweites für Kubernetes), wenn
dieser Alarm auf echten Daten beruhen soll.
