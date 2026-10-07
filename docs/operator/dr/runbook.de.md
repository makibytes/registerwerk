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
**RTO:** gemessen 2.3 s auf der nahezu leeren Drill-Datenbank (2026-10-07, `scripts/pitr-drill.sh`); nicht auf produktionsgroßen Daten gemessen, daher wird keine RTO zugesagt  
**RPO:** Ziel ≤ 15 Minuten bei aktivierter WAL-Archivierung (`archive_timeout` 300 s); der letzte Drill (2026-10-07) maß 286.8 s zwischen dem letzten committeten Datensatz und seinem archivierten WAL-Segment. Ohne WAL-Archivierung (gebündelte Dev/Test-Datenbank) ist der RPO das letzte tägliche Basis-Backup, bis zu 24 Stunden  
**Verantwortlich:** Registry Operations Team  
**DORA-Klassifizierung:** siehe die illustrative Tabelle in Abschnitt 1

---

## 1. Klassifizierung des Vorfall-Schweregrads (DORA Art. 17)

| Schweregrad | Kriterien | Aktion |
|---|---|---|
| MINOR | Einzelner Dienst ausgefallen, kein Datenverlust | Interne Warnung |
| MAJOR | Ausfall mehrerer Dienste, potenzielle Auswirkung auf Daten | Meldepflicht nach DORA Art. 19 prüfen |
| CRITICAL | Vollständiger Ausfall ODER Verletzung der Datenintegrität | Meldepflicht nach DORA Art. 19 prüfen |

Diese Tabelle ist eine illustrative Klassifizierung, keine rechtliche Bewertung. Sie ist anhand von DORA Art. 19 und der
delegierten Rechtsakte zur Klassifizierung von Vorfällen (Delegierte Verordnung (EU) 2024/1772) sowie zu Inhalt und Fristen
der Meldungen (Delegierte Verordnung (EU) 2025/301) zu prüfen. Dauerschwellen und Meldefristen sind hier bewusst nicht
festgeschrieben: Sie sind die eigene Richtlinie des Betreibers (zurückgestellte Entscheidung T9-06), und die gesetzlichen
Fristen laufen ab den in diesen Rechtsakten bestimmten Zeitpunkten, nicht ab „Erkennung“.

`POST /api/v1/dora/incidents` zeichnet einen internen Vorfall auf; es reicht keinen DORA-Bericht ein. Jede
Behörde, Frist, jedes Formular und jeder Kanal unten ist eine Prüfungsvorgabe, die extern verifiziert werden muss:
- DE: BaFin (bafin.de) Referat IT-Risikoaufsicht
- LU: CSSF über das CSS-Portal
- FR: AMF / ACPR über ONEGATE
- LI: FMA über das LIMA-Portal

---

## 2. Vollständige Postgres-Wiederherstellung (WAL-G-PITR, RPO = letztes archiviertes WAL-Segment)

!!! note "Real getestet"
    Abschnitt 2a wird von `scripts/pitr-drill.sh` auf Wegwerf-Containern geübt (nie auf der Demo-Datenbank): Basis-Backup, kontinuierliches WAL-Archiv, Totalverlust des Datenvolumens, Wiederherstellung auf einen gewählten Zielzeitpunkt, zeilengenaue Prüfung. Letzter Lauf 2026-10-07: PASSED, RPO-Stichprobe 286.8 s, RTO 2.3 s (davon `wal-g backup-fetch` 1.5 s) auf einer nahezu leeren Datenbank. Die RTO wächst mit der Größe des Basis-Backups und dem abzuspielenden WAL; vor jeder Zusage auf produktionsgroßen Daten neu messen.

!!! warning "Nur mit aktivierter WAL-Archivierung"
    Point-in-Time-Recovery braucht ein WAL-Archiv: das optionale Overlay `docker-compose.wal.yml` (WAL-G), ein verwaltetes PostgreSQL mit PITR oder CloudNativePG mit Barman Cloud (siehe [Backups und Wiederherstellung](../maintenance/backups.md)). Die gebündelte Dev/Test-Datenbank und die Standard-Compose-Demo archivieren nichts; dort ist der Wiederherstellungspunkt das letzte Basis-Backup bzw. pg_dump.

### 2a. Point-in-Time-Wiederherstellung mit WAL-G (primärer Pfad) { #2a-point-in-time-restore-from-wal-g-primary-path }

PostgreSQL-18-Mechanik: `PGDATA` ist beim offiziellen Image `/var/lib/postgresql/18/docker` (das Image deklariert `VOLUME /var/lib/postgresql`); die Wiederherstellung wird durch eine leere Datei `recovery.signal` angefordert, das Ziel steht in `postgresql.auto.conf`. Eine `recovery.conf` gibt es nicht mehr.

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

Bei S3-Speicher ersetzen Sie die `-v ..._pgarchive`-Mounts durch die Variablen `WALG_S3_PREFIX` / `AWS_*` des Quellservers. Nach der Promotion läuft die Instanz auf einer neuen Timeline: Erstellen Sie sofort ein frisches Basis-Backup (`pg-backup once`). Für die Produktionsvarianten nutzen Sie das PITR der Plattform: verwaltetes PostgreSQL (z. B. `gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>`) oder einen CloudNativePG-Recovery-Cluster (`deploy/helm/registerwerk/examples/cnpg-cluster.yaml`, unten). Diese beiden Wege werden vom Skript dieses Repositorys nicht geübt; führen Sie einen eigenen Restore-Drill durch.

Daten, die nach dem Wiederherstellungsziel (oder nach dem letzten archivierten Segment) committet wurden, gehen verloren. Vergleichen Sie `max(occurred_at)` mit dem Vorfallszeitpunkt, um den tatsächlichen Verlust zu beziffern, und gleichen Sie anschließend mit den Chain-Indexern ab.

### 2b. Wiederherstellung aus pg_dump (Fallback — RPO = letzter Dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

**`scripts/pitr-drill.sh` übt den WAL-G-Pfad (2a).** Es baut dasselbe `postgres-wal`-Image wie das Compose-Overlay, startet einen Wegwerf-Server mit `archive_mode=on`, erstellt ein Basis-Backup, fügt Zeilen vor und nach einem festgehaltenen Zielzeitpunkt ein, wartet ohne erzwungenen Segmentwechsel, bis das Segment der letzten Zeile archiviert ist (die RPO-Stichprobe), löscht Container und Datenvolumen, stellt Basis-Backup plus WAL auf den Zielzeitpunkt wieder her und prüft, dass genau die bis dahin committeten Zeilen existieren. Es gibt RPO und RTO aus und läuft bei `archive_timeout` 300 s etwa 6 Minuten. Mit `--record <backend-base-url> <operator-bearer-token>` wird das Ergebnis als `SCENARIO_BASED`-Eintrag über `POST /api/v1/dora/resilience-tests` erfasst (standardmäßig aus).

### 2d. Zeilen in einer DEFAULT-Partition

`token_transfer`, `blockchain_transaction` und `audit_event` sind monatlich partitioniert. Eine Zeile mit `occurred_at` außerhalb der vorhandenen Partitionen (z. B. falsch getaktete Indexer-Quelle oder historischer Backfill) landet in `<tabelle>_default`; Postgres kann dann die Partition dieses Monats nicht mehr anlegen. Der Alert `PartitionDefaultRowsPresent` (`registerwerk_partition_default_rows`) löst aus. Default-Partition im Wartungsfenster aufteilen (Tabelle, Monat und Daten sind Beispiele):

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

Je betroffenem Monat und Tabelle wiederholen. Einen automatischen Split-Job gibt es nicht (er braucht eine Entscheidung zum Wartungsfenster).

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
# Trigger the verification: a POST (reading /actuator/health/auditChainVerificationService only
# reports the last verdict, it triggers nothing). Requires REGISTRY_ADMIN; scripts/dr-restore-drill.sh
# --verify-audit-chain shows the full login + XSRF-TOKEN flow.
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/api/v1/audit/chain/verify | jq .
```

Wenn das Ergebnis BROKEN ist: Betrieb NICHT wieder aufnehmen. Als CRITICAL-Vorfall eskalieren; die Verletzung der Hash-Kette muss untersucht werden, bevor die Registry wieder arbeitet. Ein BROKEN-Ergebnis hält `/actuator/health` auf DOWN (die Readiness bleibt unberührt), bis ein SPÄTERER Lauf gültig ist UND das fehlerhafte Ergebnis per Vier-Augen-Prinzip quittiert wurde: `POST /api/v1/audit/verification/{id}/ack` (REGISTRY_ADMIN, Step-up, zweiter Genehmiger, Grund `AUDIT_CHAIN_VERIFICATION_ACK`, optional `note`); die Audit-Log-Seite des Operator-Portals hat die passende Schaltfläche. Das Auslösen erfolgt per POST; das Lesen von `/actuator/health/auditChainVerificationService` zeigt nur das letzte Ergebnis.

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
Kong läuft ohne Datenbank (DB-less): Die gesamte Konfiguration ist `gateway/kong.yml`, geladen beim Start (`KONG_DECLARATIVE_CONFIG`). Es gibt keine Datenbank wiederherzustellen und keinen schreibenden Admin-API-Pfad, daher greift `deck sync` nicht. Stellen Sie die Datei aus der Versionsverwaltung wieder her, validieren Sie sie und erstellen Sie den Container neu:
```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml
docker compose up -d --force-recreate kong
```
Auf Kubernetes wird dieselbe Datei als `deploy/helm/registerwerk/files/kong.yml` (ConfigMap `registerwerk-kong-config`) ausgeliefert: `helm upgrade` und das Kong-Deployment neu ausrollen.

---

## 7. Checkliste nach der Wiederherstellung
- [ ] Postgres-Zustand: `pg_isready`
- [ ] Backend-Zustand: `/actuator/health` → UP
- [ ] Audit-Chain: `POST /api/v1/audit/chain/verify` liefert `valid: true` (Abschnitt 4), danach `/actuator/health` → UP
- [ ] Indexer-Aktivität: `GET /api/v1/indexers` zeigt alle Indexer aktuell, und kein Alert `IndexerStaleCritical` / `IndexerStaleWarning` feuert
- [ ] Chain-Drift: bestätigen, dass keine offenen `chain_drift_event`-Zeilen mit severity=CRITICAL vorliegen
- [ ] Sanktionsprüfung: bestätigen, dass keine offenen `screening_hit`-Zeilen älter als 4 Std. vorliegen
- [ ] Registerübersicht: bestätigen, dass die Gesamt-Nennbeträge mit dem Snapshot vor dem Vorfall übereinstimmen
- [ ] Bei als schwerwiegend eingestuftem Vorfall: DORA-Meldungen innerhalb der Fristen des delegierten Rechtsakts einreichen (aktuellen Wortlaut prüfen, Abschnitt 1)
