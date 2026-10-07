---
title: Disaster recovery runbook
description: Draft operational runbook for Postgres and backend restore, audit chain verification, and DORA incident classification — pending operator approval and testing.
---

# Disaster Recovery Runbook

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This is a draft operational runbook, not evidence of an approved continuity plan, tested RTO/RPO,
    legally correct incident classification, or authority notification. The operator must approve,
    exercise, and reconcile it with current legal, regulatory, contractual, and infrastructure requirements.

**Service:** Registerwerk eWpG Registry  
**RPO:** target ≤ 15 minutes with WAL archiving enabled (`archive_timeout` 300 s); last drill (2026-10-07) measured 286.8 s between the last committed row and its archived WAL segment. Without WAL archiving (bundled dev/test database) the RPO is the last daily base backup, up to 24 hours  
**RTO:** measured 2.3 s on the drill's near-empty database (2026-10-07, `scripts/pitr-drill.sh`); not yet measured on production-sized data, so no RTO is promised  
**Owner:** Registry Operations Team  
**DORA classification:** see the illustrative table in section 1

---

## 1. Incident Severity Classification (DORA Art. 17)

| Severity | Criteria | Action |
|---|---|---|
| MINOR | Single service down, no data loss | Internal alert |
| MAJOR | Multi-service outage, potential data impact | Assess reporting duty under DORA Art. 19 |
| CRITICAL | Full outage OR data integrity breach | Assess reporting duty under DORA Art. 19 |

This table is an illustrative classification, not a legal determination. Verify it against DORA Art. 19
and the delegated acts on incident classification (Delegated Regulation (EU) 2024/1772) and on the
content and time limits of reports (Delegated Regulation (EU) 2025/301). Duration thresholds and
reporting deadlines are deliberately not hard-coded here: they are the operator's own policy
(parked decision T9-06) and the legal clocks run from the points those acts define, not from
"detection".

`POST /api/v1/dora/incidents` records an internal incident; it does not file a DORA report. Any
authority, deadline, form, and channel below is a review input that must be verified externally:
- DE: BaFin (bafin.de) Referat IT-Risikoaufsicht
- LU: CSSF via CSS portal
- FR: AMF / ACPR via ONEGATE
- LI: FMA via LIMA portal

---

## 2. Postgres Full Restore (WAL-G PITR, RPO = last archived WAL segment)

!!! note "Drilled for real"
    Section 2a is exercised by `scripts/pitr-drill.sh` on throwaway containers (never the demo
    database): base backup, continuous WAL archive, total loss of the data volume, restore to a
    chosen target time, row-exact verification. Last run 2026-10-07: PASSED, RPO sample 286.8 s,
    RTO 2.3 s (of which `wal-g backup-fetch` 1.5 s) on a near-empty database. The RTO scales
    with base-backup size and the WAL to replay; re-measure it on production-sized data before
    quoting it to anyone.

!!! warning "Only with WAL archiving enabled"
    Point-in-time recovery needs a WAL archive: the opt-in `docker-compose.wal.yml` overlay (WAL-G),
    a managed PostgreSQL with PITR, or CloudNativePG with Barman Cloud (see
    [Backups and Recovery](../maintenance/backups.md)). The bundled dev/test database and the stock
    Compose demo archive nothing; there the recovery point is the last base backup or pg_dump.

### 2a. Point-in-time restore from WAL-G (primary path)

PostgreSQL 18 mechanics: `PGDATA` is `/var/lib/postgresql/18/docker` on the official image (which
declares `VOLUME /var/lib/postgresql`), recovery is requested with an empty `recovery.signal` file,
and the target is set in `postgresql.auto.conf`. There is no `recovery.conf` any more.

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

With S3 storage, replace the `-v ..._pgarchive` mounts by the `WALG_S3_PREFIX` / `AWS_*` variables of
the source server. After promotion the instance runs on a new timeline: take a fresh base backup
(`pg-backup once`) before relying on it. For the production shapes use the platform's own PITR
instead: managed PostgreSQL (for example `gcloud sql instances clone <source> <target>
--point-in-time <UTC timestamp>`) or a CloudNativePG recovery cluster
(`deploy/helm/registerwerk/examples/cnpg-cluster.yaml`, bottom). Those two commands are not drilled
by this repository's script; run your own restore drill against them.

Data committed after the recovery target (or after the last archived segment) is lost. Compare
`max(occurred_at)` with the incident time to quantify the actual loss, then reconcile with the chain
indexers.

### 2b. Restore from pg_dump (fallback — RPO = last dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

**`scripts/dr-restore-drill.sh` automates this pg_dump fallback path only (`scripts/pitr-drill.sh` covers the WAL-G path of 2a)** (pg_dump the running `postgres`
compose service → restore into a disposable container → compare every table's row count →
report an RTO figure) and, with `--record-dora <backend-base-url> <bearer-token>`, files the
result as a real `SCENARIO_BASED` entry in `POST /api/v1/dora/resilience-tests` — a continuity
drill an operator actually ran, not a demo-seeded placeholder. It deliberately does not exercise
§2a, which has its own drill (`scripts/pitr-drill.sh`, below). Run it periodically (e.g. quarterly) and
after any schema change that touches migrations, to keep the "tested" in "tested continuity"
current.

With `--verify-audit-chain`, it additionally automates the audit-hash-chain half of this section:
it boots a real, throwaway backend container against the restored copy (HSM disabled for that
one throwaway container only — it needs no wallet/signing machinery, just DB connectivity — so
the flag works with only the `postgres` service running, not the full demo stack) and calls the
app's own `POST /api/v1/audit/chain/verify`, rather than reimplementing
`AuditChainVerificationService`'s SHA-256 canonicalization in bash, which would risk silently
diverging from it and giving false confidence. It needs `DEFAULT_ADMIN_EMAIL` /
`DEFAULT_ADMIN_PASSWORD` available (shell env or repo-root `.env`) matching the credentials the
*source* database was actually seeded with; without them this step is reported `SKIPPED`, not
treated as a drill failure.

**`scripts/pitr-drill.sh` drills the WAL-G path (2a).** It builds the same `postgres-wal` image the
Compose overlay uses, runs a throwaway server with `archive_mode=on`, takes a base backup, inserts
rows before and after a recorded target time, waits for the WAL segment holding the last row to be
archived without forcing a switch (the RPO sample), deletes the container and its data volume,
restores base backup plus WAL to the target time and checks that exactly the rows committed up to
the target exist. It prints the measured RPO and RTO and runs for about 6 minutes at the default
`archive_timeout` of 300 s (`--archive-timeout` shortens it, but then the RPO it measures is for
that value). With `--record <backend-base-url> <operator-bearer-token>` it files the result as a
`SCENARIO_BASED` entry in `POST /api/v1/dora/resilience-tests` (off by default):

```bash
scripts/pitr-drill.sh                                   # drill only
scripts/pitr-drill.sh --record http://localhost:48080 "$OPERATOR_TOKEN"   # drill + DORA record
```

### 2c. Promoting the read replica (Helm/Kubernetes, `values-production.yaml` only)

`values-production.yaml` optionally runs one streaming Postgres read replica alongside the
primary (`postgresql.architecture: replication`) — a real-time warm standby, not a periodic
backup. It is **not** automatic failover: nothing repoints the backend's `DB_URL` at the replica
if the primary dies, and the replica stays read-only until explicitly promoted. If the primary is
lost and the replica is intact, this is faster than a full WAL-G restore (§2a):

```bash
# 1. Confirm the replica's replication lag is low enough to accept the data loss
kubectl exec -it <release-name>-postgresql-read-0 -- \
  psql -U postgres -c "SELECT now() - pg_last_xact_replay_timestamp() AS replication_lag;"

# 2. Promote the replica out of recovery mode
kubectl exec -it <release-name>-postgresql-read-0 -- pg_ctl promote -D /bitnami/postgresql/data

# 3. Point the backend at the promoted instance
kubectl set env deployment/<release-name> \
  DB_URL="jdbc:postgresql://<release-name>-postgresql-read:5432/registerwerk"

# 4. Once the old primary is recoverable, either rebuild it as a new replica (do NOT let it
#    rejoin as primary — it and the promoted instance have now diverged) or run a fresh
#    `helm upgrade` to let the subchart recreate the primary/replica topology from scratch.
```

Any transaction not yet streamed to the replica at promotion time is lost — this is a real RPO,
not zero; like the WAL-G path above, the RPO is bounded by what was last captured (here: by replication lag, not by the archive). Practice this promotion in a
non-production namespace before relying on it during an actual incident.

### 2d. Rows in a DEFAULT partition

`token_transfer`, `blockchain_transaction` and `audit_event` are partitioned by month. A row whose
`occurred_at` falls outside the existing partitions (for example a mis-clocked indexer source or a
historic backfill) lands in `<table>_default`, and Postgres then refuses to create the partition for
that month. The `PartitionDefaultRowsPresent` alert (`registerwerk_partition_default_rows`) fires.
Split the default partition in a maintenance window:

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

Repeat per affected month and table. No automatic split job exists (it needs a maintenance-window
decision); the table, month and dates above are examples.

---

## 3. Backend Restore
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

## 4. Audit Chain Verification After Restore
```bash
# Trigger the verification: a POST (reading /actuator/health/auditChainVerificationService only
# reports the last verdict, it triggers nothing). Requires REGISTRY_ADMIN; scripts/dr-restore-drill.sh
# --verify-audit-chain shows the full login + XSRF-TOKEN flow.
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/api/v1/audit/chain/verify | jq .
```

If the verdict is BROKEN: do NOT resume operations. Escalate as a CRITICAL incident; the hash chain
violation must be investigated before the registry resumes. A BROKEN verdict keeps `/actuator/health`
DOWN (readiness is unaffected) until a LATER run is valid AND the broken verdict is acknowledged with
dual control: `POST /api/v1/audit/verification/{id}/ack` (REGISTRY_ADMIN, step-up, a second approver,
reason `AUDIT_CHAIN_VERIFICATION_ACK`, optional `note`); the operator audit-log page has the matching
button.

---

## 5. Key Material Break-Glass (replaces removed exportRaw endpoint)

Raw private key access requires all three of the following:
1. Two out of three Shamir shares (held by CTO, CFO, external Counsel)
2. Board resolution (minimum 24h advance notice to regulatory counsel)
3. Internally approved and audited break-glass record. Any regulator notification is
   incident-, operator-, and jurisdiction-specific and must follow the externally approved
   procedure; this repository does not file it with an authority.

Emergency KMS access (AWS KMS):
```bash
aws kms decrypt \
  --ciphertext-blob fileb://wallet-wrapped-dek.bin \
  --key-id arn:aws:kms:eu-central-1:ACCT:key/KEY_ID \
  --output text --query Plaintext | base64 -d > dek.bin
```

---

## 6. Kong / Gateway Restore
Kong runs DB-less: its whole configuration is `gateway/kong.yml`, loaded at start
(`KONG_DECLARATIVE_CONFIG`). There is no database to restore and no Admin API write path, so
`deck sync` does not apply. Restore the file from version control, validate it, and recreate the
container:
```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml
docker compose up -d --force-recreate kong
```
On Kubernetes the same file ships as `deploy/helm/registerwerk/files/kong.yml` (ConfigMap
`registerwerk-kong-config`): `helm upgrade` and roll out the Kong deployment.

---

## 7. Post-Recovery Checklist
- [ ] Postgres health: `pg_isready`
- [ ] Backend health: `/actuator/health` → UP
- [ ] Audit chain: `POST /api/v1/audit/chain/verify` returns `valid: true` (section 4), then `/actuator/health` → UP
- [ ] Indexer liveness: `GET /api/v1/indexers` shows every indexer current, and no `IndexerStaleCritical` / `IndexerStaleWarning` alert is firing
- [ ] Chain drift: confirm no open `chain_drift_event` rows with severity=CRITICAL
- [ ] Sanctions screening: confirm no open `screening_hit` rows older than 4h
- [ ] Registry overview: verify total nominal amounts match pre-incident snapshot
- [ ] If the incident was classified as major, file the DORA reports within the time limits of the delegated act (verify the current text, section 1)

---

## 8. Chaos Drills (Docker Compose)

Two scripts exercise real failure/recovery behavior against the running Compose stack — not a
simulation — and, for `chaos-drill.sh`, record the outcome as a real DORA `ResilienceTest` row,
the same mechanism `dr-restore-drill.sh --record-dora` already uses. Both restart whatever they
killed before exiting, but expect a brief backend restart; don't run them against a stack other
people are actively using.

```bash
scripts/chaos-drill.sh kill-postgres   # SIGKILL postgres mid-traffic; measure degrade + recovery
scripts/chaos-drill.sh kill-backend    # SIGKILL backend mid-request; measure recovery
scripts/verify-graceful-shutdown.sh    # docker stop (SIGTERM) vs. the above — contrast case
```

**`kill-backend` found a real, previously-undocumented gap the first time it ran**: Docker's
`restart: unless-stopped` policy does **not** restart a container after `docker kill` or
`docker stop` — confirmed via `docker inspect ... RestartCount` staying at 0 after the kill. It
only recovers from a genuine in-process crash the container runtime itself observes, not an
Engine-API-initiated termination. `kill-backend` now measures this honestly: it waits 20s for
automatic recovery, and if that doesn't happen, falls back to an explicit `docker start` and
records the outcome as `FINDINGS_OPEN`, not `PASSED`. The Helm/Kubernetes path does not share this
gap — `restartPolicy: Always` (the implicit Deployment default) restarts a pod after *any*
container exit, administrative or not. If self-healing from a killed container matters for the
Compose path specifically, that's a real follow-up (an external supervisor, or accepting manual
recovery as the documented model), not something either script papers over.

`verify-graceful-shutdown.sh` is the contrast case: it fires a burst of concurrent requests, sends
a real `docker stop` (SIGTERM) mid-burst, and confirms the container exits on its own within its
`stop_grace_period` (35s, `docker-compose.yml` — matched to
`spring.lifecycle.timeout-per-shutdown-phase`, `application.yml`) rather than being forced by
Docker's SIGKILL escalation, and that in-flight requests complete rather than get reset. This is
what `server.shutdown: graceful` is actually for — a normal stop/recreate, not a crash — and is
the reason `docker-compose.yml`'s backend service needs an explicit `stop_grace_period` at all:
Docker's own default stop timeout (10s) is shorter than the 30s the app is configured to use for
its own drain.
