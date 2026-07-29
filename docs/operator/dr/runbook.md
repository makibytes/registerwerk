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
# 1. Provision a new Postgres 17 instance
docker run -d --name postgres-restore postgres:17-alpine

# 2. Restore base backup
docker exec postgres-restore wal-g backup-fetch /var/lib/postgresql/data LATEST \
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

---

## 3. Backend Restore
```bash
# Pull signed image (verify Cosign signature first)
cosign verify ghcr.io/makibytes/registerwerk/backend:VERSION

# Deploy with production environment
docker run -d \
  --env-file /etc/registerwerk/prod.env \
  -e REGISTERWERK_PRODUCTION_MODE=true \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/makibytes/registerwerk/backend:VERSION

# Verify health
curl http://localhost:8080/actuator/health | jq .status
```

---

## 4. Audit Chain Verification After Restore
```bash
# Trigger audit chain verification via actuator
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/actuator/health/auditChainVerificationService | jq .

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
  --kong-addr http://localhost:8001
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
