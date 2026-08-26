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
**RTO target:** ≤4 hours (eWpRV §6)  
**RPO target:** ≤15 minutes (WAL archiving via wal-g)  
**Owner:** Registry Operations Team  
**DORA classification:** MAJOR incident if >4h downtime

---

## 1. Incident Severity Classification (DORA Art. 17)

| Severity | Criteria | Action | Deadline |
|---|---|---|---|
| MINOR | Single service down, &lt;30 min, no data loss | Internal alert | — |
| MAJOR | Multi-service, 30 min–4h, potential data impact | Initial report to BaFin/CSSF/AMF/FMA | 72h from detection |
| CRITICAL | Full outage >4h OR data integrity breach | Initial report to competent authority | 4h from detection |

`POST /api/v1/dora/incidents` records an internal incident; it does not file a DORA report. Any
authority, deadline, form, and channel below is a review input that must be verified externally:
- DE: BaFin (bafin.de) Referat IT-Risikoaufsicht
- LU: CSSF via CSS portal
- FR: AMF / ACPR via ONEGATE
- LI: FMA via LIMA portal

---

## 2. Postgres Full Restore (RPO ≤15 min)

### 2a. Restore from wal-g backup (primary path)
```bash
# 1. Provision a new Postgres 18.6 instance
docker run -d --name postgres-restore postgres:18.6-alpine

# 2. Restore base backup — PGDATA lives under /var/lib/postgresql/18/docker on this image,
#    which declares VOLUME /var/lib/postgresql (not .../data as on pg17 and earlier); restoring
#    into the old .../data path would silently write outside the volume the image actually reads.
docker exec postgres-restore wal-g backup-fetch /var/lib/postgresql LATEST \
  --walg-s3-prefix s3://registerwerk-backups/wal-g

# 3. Replay WAL to target time
cat > /tmp/recovery.conf << EOF
restore_command = 'wal-g wal-fetch "%f" "%p"'
recovery_target_time = '$(date -u -d "-15 minutes" +"%Y-%m-%d %H:%M:%S")'
recovery_target_action = 'promote'
EOF

# 4. Start Postgres and wait for recovery
docker start postgres-restore
docker logs -f postgres-restore | grep "recovery is complete"

# 5. Validate row counts
psql -h localhost -U registerwerk -c "SELECT count(*) FROM audit_event;"
psql -h localhost -U registerwerk -c "SELECT max(occurred_at) FROM audit_event;"
```

### 2b. Restore from pg_dump (fallback — RPO = last dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

**`scripts/dr-restore-drill.sh` automates this fallback path** (pg_dump the running `postgres`
compose service → restore into a disposable container → compare every table's row count →
report an RTO figure) and, with `--record-dora <backend-base-url> <bearer-token>`, files the
result as a real `SCENARIO_BASED` entry in `POST /api/v1/dora/resilience-tests` — a continuity
drill an operator actually ran, not a demo-seeded placeholder. It deliberately does not exercise
§2a (wal-g/S3) — that needs real backup-bucket credentials this environment doesn't have, and
simulating it would produce a result nobody could trust. Run it periodically (e.g. quarterly) and
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
not zero, exactly like the WAL-G path's ≤15 min target above. Practice this promotion in a
non-production namespace before relying on it during an actual incident.

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
# Trigger audit chain verification via actuator
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/actuator/health/auditChainVerificationService | jq .

# If BROKEN: do NOT resume operations. Escalate to DORA CRITICAL incident.
# The hash chain violation must be investigated before the registry resumes.
```

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
```bash
deck gateway sync gateway/kong.yml \
  --kong-addr http://localhost:48001
```

---

## 7. Post-Recovery Checklist
- [ ] Postgres health: `pg_isready`
- [ ] Backend health: `/actuator/health` → UP
- [ ] Audit chain: `/actuator/health/auditChainVerificationService` → UP
- [ ] Indexer liveness: `/actuator/health/indexerMonitor` → UP
- [ ] Chain drift: confirm no open `chain_drift_event` rows with severity=CRITICAL
- [ ] Sanctions screening: confirm no open `screening_hit` rows older than 4h
- [ ] Registry overview: verify total nominal amounts match pre-incident snapshot
- [ ] File DORA final report within 1 month of incident resolution

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
