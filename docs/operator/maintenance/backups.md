---
title: Backups and Recovery
---

# Backups and Recovery

The registry's persistent state lives in two places:
1. **PostgreSQL** — all registry data (issuances, entities, audit log, KYC, indexer state)
2. **S3 / object storage** — KYC documents larger than 5 MB

Smart contract state lives on the blockchain and is inherently replicated — it does not need to be backed up separately.

## PostgreSQL backup strategy

The two deployment paths documented in the repo's `CLAUDE.md` use two different, **non-
interchangeable** backup mechanisms. Follow whichever section matches how you're actually
running Registerwerk — previously this page only ever described the Docker Compose path, which
would mislead an operator running the Helm/Kubernetes deployment (Phase 12, finding #6).

### Docker Compose deployment — pg_dump

Add this cron job to your server (or run as a Docker container):

```bash
# /etc/cron.d/ewpg-backup
0 2 * * * root docker exec registerwerk-postgres-1 \
  pg_dump -U registerwerk registerwerk | gzip \
  > /backups/postgres/registerwerk-$(date +%Y%m%d-%H%M%S).sql.gz
```

Retain daily backups for 30 days, weekly backups for 12 months:

```bash
# Delete daily backups older than 30 days
find /backups/postgres -name "*.sql.gz" -mtime +30 -delete
```

### Helm/Kubernetes deployment — WAL-G continuous archiving

The Docker Compose pg_dump approach above does **not** apply here. `deploy/helm/backup/` is a
separate Helm chart (installed as its own release, alongside — not merged into — the main
`deploy/helm/registerwerk` chart) that runs a daily WAL-G `CronJob` archiving continuous backups
to S3. Before installing it, you must set two chart values it cannot derive on its own:

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0
```

See `deploy/helm/backup/values.yaml` for the full set of options (S3 bucket/region, retention,
IRSA vs. static S3 credentials, optional Prometheus Pushgateway URL for the staleness metric
below).

For either path, [pgBackRest](https://pgbackrest.org/) or [Barman](https://pgbarman.org/) remain
reasonable alternatives if you'd rather not run WAL-G directly.

### Testing backups

Test your backup and restore procedure at least monthly:

```bash
# Restore a backup to a test database
gunzip -c /backups/postgres/registerwerk-20250401-020000.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U registerwerk registerwerk_test
```

Verify key tables are present and data counts are sensible:

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

## S3 document backup

Enable S3 versioning and cross-region replication for your KYC document bucket:

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

## Disaster recovery

### Full restore from pg_dump backup (Docker Compose deployment)

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

### Restore from WAL-G backup (Helm/Kubernetes deployment)

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

### Recovery time objectives

| Scenario | RTO (Recovery Time Objective) |
|----------|-------------------------------|
| Single service restart | < 1 minute |
| Database restore from daily backup | < 30 minutes |
| Full server rebuild from scratch | < 4 hours |
| Smart-contract state | Restore is not controlled by the application backup. Recover application projections separately and reconcile them with the configured chain and the instrument-specific legal register; the blockchain is not universally authoritative. |

## Backup monitoring

`monitoring/alerts/registerwerk.yml` already includes a `BackupStale` rule querying
`backup_last_success_timestamp`. That metric only exists if something actually pushes it — a
CronJob is ephemeral and can't be scraped directly, so both backup paths push it to a Prometheus
Pushgateway on success:

- **Helm/Kubernetes (WAL-G)**: set `monitoring.pushgatewayUrl` in `deploy/helm/backup/values.yaml`
  — the CronJob pushes automatically once set (see `templates/backup-cronjob.yaml`).
- **Docker Compose (pg_dump)**: add the equivalent push to the end of your cron script:

  ```bash
  curl -s -X POST --data-binary "backup_last_success_timestamp $(date +%s)" \
    "$PUSHGATEWAY_URL/metrics/job/registerwerk_pg_backup"
  ```

Neither path ships a Pushgateway by default — add one to whichever monitoring stack you're
running (`monitoring/docker-compose.yml` for Compose, or a cluster-wide one for Kubernetes) if
you want this alert to have real data behind it.
