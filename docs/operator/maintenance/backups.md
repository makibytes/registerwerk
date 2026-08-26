---
title: Backups and Recovery
---

# Backups and Recovery

The registry's persistent state lives in two places:
1. **PostgreSQL** — all registry data (issuances, entities, audit log, KYC document metadata and
   inline content ≤5 MB, indexer state)
2. **S3 / object storage** — two separate buckets, both outside the Postgres backup entirely:
   `S3_BUCKET` (`registerwerk-documents` by default) for KYC documents over the 5 MB inline
   threshold, and `REGISTERWERK_REPORTING_S3_BUCKET` (`registerwerk-reports` by default) for
   generated regulatory reports (`regreporting` module). Apply the versioning/replication
   guidance below to **both**.

Smart contract state lives on the blockchain and is inherently replicated — it does not need to be backed up separately.

## PostgreSQL backup strategy

The two deployment paths documented in the repo's `CLAUDE.md` use two different, **non-
interchangeable** backup mechanisms. Follow whichever section matches how you're actually
running Registerwerk.

### Docker Compose deployment — manual pg_dump

This is the demo/local single-host showcase (see `CLAUDE.md`'s "Not a production topology"
warning), not something meant to simulate a real backup story — `docker compose up` does not run
any automated backup service. Real deployments run PostgreSQL as a managed service (Cloud SQL on
GKE), which handles automated backup/point-in-time-recovery itself; see the Helm/Kubernetes
WAL-G section below for the actual production backup path this repo ships.

For an ad-hoc manual dump of the local demo database:

```bash
docker compose exec postgres pg_dump -U ${DB_USER:-registerwerk} --no-owner --no-privileges registerwerk \
  | gzip > registerwerk-$(date -u +%Y%m%d-%H%M%S).sql.gz
```

There is no scheduled retention or offsite replication for this local file — it is a one-off,
manual convenience, not a backup strategy.

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

Test your backup and restore procedure at least monthly. `scripts/dr-restore-drill.sh` automates
this against the Docker Compose path end to end (pg_dump → restore into a disposable container →
row-count comparison → optionally `--verify-audit-chain`, see
[the DR runbook](../dr/runbook.md#2b-restore-from-pg_dump-fallback-rpo-last-dump)) — prefer it
over the manual steps below for anything beyond a one-off spot check:

```bash
# Take a fresh manual dump (see above) and restore it into a test database
docker compose exec postgres pg_dump -U registerwerk --no-owner --no-privileges registerwerk \
  | gzip > registerwerk-test.sql.gz
gunzip -c registerwerk-test.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U registerwerk registerwerk_test
```

Verify key tables are present and data counts are sensible:

```sql
SELECT 'legal_entities' AS tbl, COUNT(*) FROM legal_entity
UNION ALL
SELECT 'assets', COUNT(*) FROM asset
UNION ALL
SELECT 'deployments', COUNT(*) FROM asset_deployment
UNION ALL
SELECT 'transfers', COUNT(*) FROM token_transfer
UNION ALL
SELECT 'audit_events', COUNT(*) FROM audit_event;
```

## S3 document backup

Enable S3 versioning and cross-region replication for **both** document buckets — the KYC
document bucket (`S3_BUCKET`) and the regulatory-reports bucket
(`REGISTERWERK_REPORTING_S3_BUCKET`); neither is covered by the Postgres backup paths above, and
neither ships versioning/replication enabled by default:

```bash
# Enable versioning (repeat for each bucket)
aws s3api put-bucket-versioning \
  --bucket your-kyc-bucket \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-versioning \
  --bucket your-reports-bucket \
  --versioning-configuration Status=Enabled

# Enable cross-region replication (requires a destination bucket in another region; repeat for
# each bucket with its own replication.json)
aws s3api put-bucket-replication \
  --bucket your-kyc-bucket \
  --replication-configuration file://replication.json
```

This repository does not provision these buckets itself (no Terraform/CloudFormation for AWS
resources) — versioning and replication are the operator's responsibility to configure once, out
of band, against whatever bucket `S3_BUCKET`/`REGISTERWERK_REPORTING_S3_BUCKET` actually point at.

## Disaster recovery

### Full restore from pg_dump backup (Docker Compose deployment)

```bash
# Stop the backend to prevent writes during restore
docker compose stop backend

# Drop and recreate the database
docker exec registerwerk-postgres-1 \
  psql -U registerwerk -c "DROP DATABASE registerwerk; CREATE DATABASE registerwerk;"

# Restore from whatever manual dump you took beforehand (see "Docker Compose deployment" above —
# this local/demo path has no automated backup service to pull one from)
gunzip -c registerwerk-latest.sql.gz \
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
CronJob is ephemeral and can't be scraped directly, so the Helm/Kubernetes WAL-G CronJob pushes it
to a Prometheus Pushgateway on success (set `monitoring.pushgatewayUrl` in
`deploy/helm/backup/values.yaml`; see `templates/backup-cronjob.yaml`), and Pushgateway rejects a
POST body without a trailing newline (HTTP 400), silently, unless you check for it.

The Docker Compose deployment has no automated backup service at all (see above), so this alert
has no effect there — `time() - backup_last_success_timestamp` never matches an absent series,
which Prometheus correctly treats as "no alert," not "always firing." This is expected: the
Compose path's backup story is intentionally not production-grade.
