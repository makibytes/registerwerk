---
id: backups
title: Backups and Recovery
sidebar_label: Backups
---

# Backups and Recovery

The registry's persistent state lives in two places:
1. **PostgreSQL** — all registry data (issuances, entities, audit log, KYC, indexer state)
2. **S3 / object storage** — KYC documents larger than 5 MB

Smart contract state lives on the blockchain and is inherently replicated — it does not need to be backed up separately.

## PostgreSQL backup strategy

### Automated daily backup with pg_dump

Add this cron job to your server (or run as a Docker container):

```bash
# /etc/cron.d/ewpg-backup
0 2 * * * root docker exec registerwerk-postgres-1 \
  pg_dump -U ewpg ewpg | gzip \
  > /backups/postgres/ewpg-$(date +%Y%m%d-%H%M%S).sql.gz
```

Retain daily backups for 30 days, weekly backups for 12 months:

```bash
# Delete daily backups older than 30 days
find /backups/postgres -name "*.sql.gz" -mtime +30 -delete
```

### Continuous WAL archiving (production recommended)

For production, configure PostgreSQL WAL archiving to S3 for point-in-time recovery (PITR):

```ini
# postgresql.conf
wal_level = replica
archive_mode = on
archive_command = 'aws s3 cp %p s3://your-backup-bucket/wal/%f'
archive_timeout = 300
```

Use [pgBackRest](https://pgbackrest.org/) or [Barman](https://pgbarman.org/) for managed WAL archiving and automated backup retention.

### Testing backups

Test your backup and restore procedure at least monthly:

```bash
# Restore a backup to a test database
gunzip -c /backups/postgres/ewpg-20250401-020000.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U ewpg ewpg_test
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

### Full restore from pg_dump backup

```bash
# Stop the backend to prevent writes during restore
docker compose stop backend

# Drop and recreate the database
docker exec registerwerk-postgres-1 \
  psql -U ewpg -c "DROP DATABASE ewpg; CREATE DATABASE ewpg;"

# Restore
gunzip -c /backups/postgres/ewpg-latest.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U ewpg ewpg

# Restart the backend — Flyway will verify the schema
docker compose start backend
```

### Recovery time objectives

| Scenario | RTO (Recovery Time Objective) |
|----------|-------------------------------|
| Single service restart | < 1 minute |
| Database restore from daily backup | < 30 minutes |
| Full server rebuild from scratch | < 4 hours |
| Smart contract data | N/A (blockchain is the source of truth) |

## Backup monitoring

Add a Prometheus alert for backup staleness:

```yaml
- alert: BackupStale
  expr: time() - backup_last_success_timestamp > 86400
  labels:
    severity: critical
  annotations:
    summary: "PostgreSQL backup has not run in over 24 hours"
```

Implement `backup_last_success_timestamp` as a custom Prometheus gauge written by your backup script on successful completion.
sidebar_position: 2
---

# Backups

## PostgreSQL

### Automated backup

```bash
# Daily backup (add to cron)
pg_dump -h $DB_HOST -U $DB_USER -Fc ewpg_registry > backup_$(date +%Y%m%d).dump
```

### Point-in-time recovery

Enable WAL archiving in `postgresql.conf`:
```ini
wal_level = replica
archive_mode = on
archive_command = 'aws s3 cp %p s3://your-bucket/wal/%f'
```

### Audit log archival

Monthly partitions can be detached and archived:

```sql
-- Detach old partition (read-only, safe for archival)
ALTER TABLE audit_event DETACH PARTITION audit_event_2025_01;

-- Export to S3
COPY audit_event_2025_01 TO PROGRAM 'aws s3 cp - s3://archive/audit_2025_01.csv' CSV HEADER;
```

## S3 KYC documents

Enable S3 versioning on the KYC bucket:
```bash
aws s3api put-bucket-versioning \
  --bucket $AWS_S3_BUCKET \
  --versioning-configuration Status=Enabled
```

## graph-node state

graph-node stores its indexed state in its own PostgreSQL database. Back this up alongside the application DB. On restore, graph-node will resume from its last checkpoint.

## Recovery checklist

1. Restore application PostgreSQL from dump
2. Restore graph-node PostgreSQL
3. Verify `indexer_state` cursors are consistent with graph-node state
4. Start services: `docker compose up -d`
5. Verify health endpoints
6. Monitor `indexer_state.last_synced_at` for catch-up
