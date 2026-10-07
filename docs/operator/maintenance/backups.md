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
production shapes below for the backup path this repo targets.

For an ad-hoc manual dump of the local demo database:

```bash
docker compose exec postgres pg_dump -U ${DB_USER:-registerwerk} --no-owner --no-privileges registerwerk \
  | gzip > registerwerk-$(date -u +%Y%m%d-%H%M%S).sql.gz
```

There is no scheduled retention or offsite replication for this local file — it is a one-off,
manual convenience, not a backup strategy.

### Docker Compose deployment — opt-in WAL archiving and PITR

The default stack above is untouched. The `docker-compose.wal.yml` overlay is an explicit opt-in that
adds continuous WAL archiving and point-in-time recovery with [WAL-G](https://github.com/wal-g/wal-g):

```bash
# postgres with archive_mode=on (recreates the container; the pg18data volume is reused as is)
docker compose -f docker-compose.yml -f docker-compose.wal.yml up -d postgres
# daily base backups + retention (profile "backup"); one-shot variant: ... run --rm pg-backup once
docker compose -f docker-compose.yml -f docker-compose.wal.yml --profile backup up -d pg-backup
```

The overlay swaps the image for `registerwerk/postgres-wal:18.6` (`postgres-wal/`: the same
`postgres:18.6-alpine` base plus WAL-G built from a pinned tag, so an existing volume needs no
dump/restore) and sets `archive_mode=on`, `archive_command='wal-g wal-push %p'` and
`archive_timeout`. Settings (environment or `.env`):

| Variable | Default | Meaning |
|---|---|---|
| `PG_ARCHIVE_TIMEOUT` | `300` | Seconds after which an active server must switch and archive its WAL segment. This is the RPO knob: the RPO is bounded by it plus upload time |
| `WALG_FILE_PREFIX` | `/wal-archive` | Local archive (the `pgarchive` volume). Same host as the database: protects against logical loss and corruption, **not** against losing the machine |
| `WALG_S3_PREFIX` + `AWS_ENDPOINT`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_FORCE_PATH_STYLE` | empty | S3-compatible archive (wins over the file prefix); the bucket must already exist. The `minio` service of the `audit-anchor` profile works for a local demo |
| `BACKUP_INTERVAL_SECONDS` / `BACKUP_RETAIN_FULL` | `86400` / `7` | Base-backup cadence and number of full backups kept |
| `BACKUP_PUSHGATEWAY_URL` | empty | Receives `backup_last_success_timestamp` (the `BackupStale` metric) after each backup |

Restore and the measured numbers: [DR runbook, section 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path).
The S3 mode is configured by the same variables but was not exercised by the drill (it used the
local archive).

### Helm/Kubernetes deployment — three database shapes

The main chart declares which shape a release relies on (`database.shape` in
`deploy/helm/registerwerk/values.yaml`; the chart refuses combinations that contradict
`postgresql.enabled`, and `NOTES.txt` prints what the shape does and does not cover). Target for the
two production shapes: **RPO ≤ 15 minutes** through continuous WAL archiving
(`archive_timeout` 300 s).

| `database.shape` | PostgreSQL | Recovery point | Use |
|---|---|---|---|
| `managed` | Managed service with PITR (Cloud SQL via `cloudSqlProxy`, RDS, Azure Flexible Server, ...); `postgresql.enabled=false` | Provider PITR (continuous log archive) | Production |
| `cnpg` | CloudNativePG cluster with Barman Cloud WAL archiving to object storage, operated outside this chart; `postgresql.enabled=false` | Continuous WAL archive (`archive_timeout` 300 s) | Production |
| `bundled` (default) | Bitnami subchart, single primary | Last daily base backup (up to 24 h), no PITR | Dev/test only |

#### Managed PostgreSQL with PITR (`database.shape: managed`)

Switch these on at the provider; the chart cannot verify them, so treat them as an acceptance checklist:

- Point-in-time recovery / continuous transaction-log archiving enabled, with at least 7 days of
  log retention (Cloud SQL: `--enable-point-in-time-recovery --retained-transaction-log-days=7`;
  RDS: automated-backup retention of at least 7 days; Azure Flexible Server: PITR retention 7 to 35 days).
- Automated daily backups enabled with enough retained backups to cover your retention policy, high
  availability (regional/multi-AZ) and deletion protection on.
- Do not set `archive_mode`/`archive_command` yourself; the provider owns them. The RPO is whatever
  log-shipping interval the provider documents (confirm the figure in the contract/SLA you rely on).
- Alert on the provider's own archive/PITR health metric, and rehearse a real point-in-time clone
  into a scratch instance (`gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>`
  on Cloud SQL) before you need it. `scripts/pitr-drill.sh` does not drive a managed service.
- Provision the DML-only runtime login once with `postgres-init/roles/ensure-runtime-role.sh`.

#### CloudNativePG with Barman Cloud (`database.shape: cnpg`)

`deploy/helm/registerwerk/examples/cnpg-cluster.yaml` is a complete example: `ObjectStore` (S3
destination), a 3-instance `Cluster` with `archive_timeout: "300"`, the Barman Cloud plugin as WAL
archiver, a separate WAL volume, `enablePodMonitor` (exposes the `cnpg_pg_stat_archiver_*` metrics the
`WalArchiveLag` alert reads), a daily `ScheduledBackup`, and a commented-out recovery `Cluster` with
`recoveryTarget.targetTime`. Install the operator and plugin first, then apply the manifest, set
`database.shape=cnpg`, `postgresql.enabled=false` and point `env.DB_URL` at the `-rw` Service. The
manifest has not been applied to a live cluster in this repository; validate it against your operator
and plugin versions.

#### Bundled PostgreSQL (`database.shape: bundled`, dev/test)

`deploy/helm/backup/` is a separate Helm chart (installed as its own release, alongside, not merged
into, the main chart) that runs a daily WAL-G `CronJob` pushing a **base backup** of the bundled
database to S3.

!!! warning "RPO is the last daily base backup"
    The bundled database does not archive WAL, so the recovery point is the last successful base
    backup (up to 24 hours) and point-in-time recovery is not available. A failing
    `wal-g backup-push` fails the job, and a failed Pushgateway push also fails it, so `BackupStale`
    fires. For the 15-minute target use `managed` or `cnpg`.

Before installing it, set three chart values it cannot derive on its own:

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0 \
  --set postgresql.existingSecret=<main chart's postgresql.auth.existingSecret>
```

See `deploy/helm/backup/values.yaml` for the full set of options (S3 bucket/region, retention,
IRSA vs. static S3 credentials, optional Prometheus Pushgateway URL for the staleness metric
below). [pgBackRest](https://pgbackrest.org/) remains a reasonable alternative to WAL-G.

### Testing backups

#### PITR drill (`scripts/pitr-drill.sh`)

Runs the whole WAL-G path on throwaway containers and volumes (never the demo database): base
backup, WAL archive, total loss of the data volume, restore to a recorded target time, and a
row-exact check. It prints the measured RPO sample and RTO and takes about 6 minutes at the default
`archive_timeout`. `--record <backend-base-url> <operator-bearer-token>` files the result as a
`SCENARIO_BASED` entry via `POST /api/v1/dora/resilience-tests` (off by default). Last run
2026-10-07: **PASSED**, RPO sample **286.8 s** (bound: `archive_timeout` 300 s plus upload time, target
≤ 15 min), RTO **2.3 s** (of which `wal-g backup-fetch` 1.5 s) on a near-empty database.
The RTO is not a promise for production: it grows with base-backup size and the WAL to replay, so
re-run the drill (and time the restore) against production-sized data.

#### Logical dump check

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

### Point-in-time restore (WAL-G, CloudNativePG, managed)

The step-by-step PITR procedure (PostgreSQL 18: `recovery.signal` and `recovery_target_time` in
`postgresql.auto.conf`, no `recovery.conf`) is in [the DR runbook, section 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path).
For CloudNativePG use the recovery `Cluster` at the bottom of `examples/cnpg-cluster.yaml`; for a
managed service use the provider's point-in-time clone. The bundled dev/test database can only be
restored to its last base backup (`wal-g backup-fetch /bitnami/postgresql/data LATEST` from a pod
with the data PVC mounted, scaling the backend to zero first).

### Recovery objectives

| Item | Value | Basis |
|---|---|---|
| RPO, WAL archiving enabled | ≤ 5 min under write activity (`archive_timeout` 300 s + upload); target ≤ 15 min | 2026-10-07 drill measured 286.8 s |
| RPO, bundled dev/test database | Up to 24 h (last daily base backup) | By construction, no WAL archive |
| RTO, PITR restore | 2.3 s on the near-empty drill database | 2026-10-07 drill; not measured on production-sized data |
| RTO, full server rebuild | Not measured | No drill; no figure is promised |
| Smart-contract state | Restore is not controlled by the application backup. Recover application projections separately and reconcile them with the configured chain and the instrument-specific legal register; the blockchain is not universally authoritative. | |

## Backup monitoring

`monitoring/alerts/registerwerk.yml` includes a `BackupStale` rule (stale over 24 h **or** the
series absent) and a `BackupMetricAbsent` rule (series gone for 2 days) on
`backup_last_success_timestamp`, plus `BackupPushStale` on the Pushgateway push time. That metric only exists if something actually pushes it — a
CronJob is ephemeral and can't be scraped directly, so the Helm/Kubernetes WAL-G CronJob pushes it
to a Prometheus Pushgateway on success (set `monitoring.pushgatewayUrl` in
`deploy/helm/backup/values.yaml`; see `templates/backup-cronjob.yaml`), and Pushgateway rejects a
POST body without a trailing newline (HTTP 400), silently, unless you check for it.

WAL archiving has its own alerts in the same file: `WalArchiveLag` (no WAL segment archived for more
than 15 minutes, for 5 minutes), `WalArchiveFailing` (`pg_stat_archiver.failed_count` growing) and
`WalArchiveMetricAbsent` (neither source metric scraped, so the lag alert could never fire). They read
one of two sources:

- `cnpg_pg_stat_archiver_seconds_since_last_archival` and `cnpg_pg_stat_archiver_failed_count`
  from CloudNativePG's built-in monitoring (`enablePodMonitor`; check the names against your CNPG version).
- `pg_wal_archive_last_archive_age_seconds` and `pg_wal_archive_failed_count` from a custom query on
  `pg_stat_archiver` in your PostgreSQL exporter (for example `postgres_exporter`'s queries file;
  confirm your exporter version still supports custom queries):

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

The registry always has write activity, so with `archive_timeout` the archive age stays below the
timeout; an age above 15 minutes means archiving is stalled rather than the database idle. Managed
services expose their own equivalent: wire it to the same alert or alert on the provider's metric.
`monitoring/alerts/tests/registerwerk.test.yml` unit-tests these rules (`promtool test rules`).

The Docker Compose deployment has no automated backup service at all (see above), so this alert
is expected to fire there once Prometheus runs: the series is absent, which `BackupStale` now treats
as a failure. The Compose path's backup story is intentionally not production-grade; mute the
alert in the local observability demo.
