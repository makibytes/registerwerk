---
title: Disaster-Recovery-Runbook
description: Entwurf eines operativen Runbooks für Postgres- und Backend-Wiederherstellung, Audit-Chain-Verifizierung und DORA-Vorfallklassifizierung — vorbehaltlich Betreiberfreigabe und Test.
---

# Disaster-Recovery-Runbook

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Dies ist ein Entwurf eines operativen Runbooks, kein Nachweis eines genehmigten Kontinuitätsplans, getesteter
    RTO/RPO-Werte, rechtlich korrekter Vorfallklassifizierung oder Behördenbenachrichtigung. Der Betreiber muss es
    genehmigen, testen und mit den aktuellen gesetzlichen, aufsichtsrechtlichen, vertraglichen und infrastrukturellen
    Anforderungen abgleichen.

**Dienst:** Registerwerk eWpG-Register  
**RTO-Ziel:** ≤4 Stunden (eWpRV §6)  
**RPO-Ziel:** ≤15 Minuten (WAL-Archivierung via wal-g)  
**Verantwortlich:** Registry Operations Team  
**DORA-Klassifizierung:** MAJOR-Vorfall bei >4 Stunden Ausfallzeit

---

## 1. Klassifizierung des Vorfall-Schweregrads (DORA Art. 17)

| Schweregrad | Kriterien | Aktion | Frist |
|---|---|---|---|
| MINOR | Einzelner Dienst ausgefallen, &lt;30 Min., kein Datenverlust | Interne Warnung | — |
| MAJOR | Mehrere Dienste, 30 Min.–4 Std., potenzielle Auswirkung auf Daten | Erstmeldung an BaFin/CSSF/AMF/FMA | 72 Std. ab Erkennung |
| CRITICAL | Vollständiger Ausfall >4 Std. ODER Verletzung der Datenintegrität | Erstmeldung an zuständige Behörde | 4 Std. ab Erkennung |

`POST /api/v1/dora/incidents` zeichnet einen internen Vorfall auf; es reicht keinen DORA-Bericht ein. Jede
Behörde, Frist, jedes Formular und jeder Kanal unten ist eine Prüfungsvorgabe, die extern verifiziert werden muss:
- DE: BaFin (bafin.de) Referat IT-Risikoaufsicht
- LU: CSSF über das CSS-Portal
- FR: AMF / ACPR über ONEGATE
- LI: FMA über das LIMA-Portal

---

## 2. Vollständige Postgres-Wiederherstellung (RPO ≤15 Min.)

### 2a. Wiederherstellung aus wal-g-Backup (primärer Pfad)
```bash
# 1. Provision a new Postgres 18.6 instance
docker run -d --name postgres-restore postgres:18.6-alpine

# 2. Restore base backup
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

### 2b. Wiederherstellung aus pg_dump (Fallback — RPO = letzter Dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

---

## 3. Backend-Wiederherstellung
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

## 4. Audit-Chain-Verifizierung nach der Wiederherstellung
```bash
# Trigger audit chain verification via actuator
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/actuator/health/auditChainVerificationService | jq .

# If BROKEN: do NOT resume operations. Escalate to DORA CRITICAL incident.
# The hash chain violation must be investigated before the registry resumes.
```

---

## 5. Break-Glass für Schlüsselmaterial (ersetzt den entfernten exportRaw-Endpunkt)

Roher Zugriff auf private Schlüssel erfordert alle drei der folgenden Punkte:
1. Zwei von drei Shamir-Anteilen (gehalten von CTO, CFO, externer Rechtsberatung)
2. Vorstandsbeschluss (mindestens 24 Std. Vorlauf für die aufsichtsrechtliche Beratung)
3. Intern genehmigter und geprüfter Break-Glass-Datensatz. Jede Behördenbenachrichtigung ist
   vorfalls-, betreiber- und jurisdiktionsspezifisch und muss dem extern genehmigten Verfahren
   folgen; dieses Repository reicht sie nicht bei einer Behörde ein.

Notfallzugriff via KMS (AWS KMS):
```bash
aws kms decrypt \
  --ciphertext-blob fileb://wallet-wrapped-dek.bin \
  --key-id arn:aws:kms:eu-central-1:ACCT:key/KEY_ID \
  --output text --query Plaintext | base64 -d > dek.bin
```

---

## 6. Kong-/Gateway-Wiederherstellung
```bash
deck gateway sync gateway/kong.yml \
  --kong-addr http://localhost:48001
```

---

## 7. Checkliste nach der Wiederherstellung
- [ ] Postgres-Zustand: `pg_isready`
- [ ] Backend-Zustand: `/actuator/health` → UP
- [ ] Audit-Chain: `/actuator/health/auditChainVerificationService` → UP
- [ ] Indexer-Aktivität: `/actuator/health/indexerMonitor` → UP
- [ ] Chain-Drift: bestätigen, dass keine offenen `chain_drift_event`-Zeilen mit severity=CRITICAL vorliegen
- [ ] Sanktionsprüfung: bestätigen, dass keine offenen `screening_hit`-Zeilen älter als 4 Std. vorliegen
- [ ] Registerübersicht: bestätigen, dass die Gesamt-Nennbeträge mit dem Snapshot vor dem Vorfall übereinstimmen
- [ ] DORA-Abschlussbericht innerhalb eines Monats nach Vorfallslösung einreichen
