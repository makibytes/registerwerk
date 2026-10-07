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
fährt, in die Irre führen würde.

### Docker-Compose-Bereitstellung — manueller pg_dump

Dies ist die lokale Demo-/Einzelhost-Variante (siehe die Warnung „Not a production topology“ in `CLAUDE.md`), keine Simulation einer echten Backup-Strategie: `docker compose up` startet keinen automatischen Backup-Dienst. Echte Deployments betreiben PostgreSQL als verwalteten Dienst (Cloud SQL auf GKE) mit eigenem Backup/PITR; siehe die Produktionsvarianten unten. Manueller Dump der lokalen Demo-Datenbank:

```bash
docker compose exec postgres pg_dump -U ${DB_USER:-registerwerk} --no-owner --no-privileges registerwerk \
  | gzip > registerwerk-$(date -u +%Y%m%d-%H%M%S).sql.gz
```

Für diese lokale Datei gibt es weder geplante Aufbewahrung noch Offsite-Replikation — sie ist eine einmalige, manuelle Hilfe, keine Backup-Strategie.

### Docker-Compose-Bereitstellung — optionale WAL-Archivierung und PITR

Der Standard-Stack oben bleibt unverändert. Das Overlay `docker-compose.wal.yml` ist ein ausdrückliches Opt-in und ergänzt kontinuierliche WAL-Archivierung und Point-in-Time-Recovery mit [WAL-G](https://github.com/wal-g/wal-g):

```bash
# postgres with archive_mode=on (recreates the container; the pg18data volume is reused as is)
docker compose -f docker-compose.yml -f docker-compose.wal.yml up -d postgres
# daily base backups + retention (profile "backup"); one-shot variant: ... run --rm pg-backup once
docker compose -f docker-compose.yml -f docker-compose.wal.yml --profile backup up -d pg-backup
```

Das Overlay ersetzt das Image durch `registerwerk/postgres-wal:18.6` (`postgres-wal/`: dieselbe Basis `postgres:18.6-alpine` plus WAL-G aus einem gepinnten Tag, ein vorhandenes Volume braucht daher kein Dump/Restore) und setzt `archive_mode=on`, `archive_command='wal-g wal-push %p'` und `archive_timeout`. Einstellungen (Umgebung oder `.env`):

| Variable | Standard | Bedeutung |
|---|---|---|
| `PG_ARCHIVE_TIMEOUT` | `300` | Sekunden, nach denen ein aktiver Server sein WAL-Segment wechseln und archivieren muss. Das ist der RPO-Regler: Der RPO ist durch diesen Wert plus Upload-Zeit begrenzt |
| `WALG_FILE_PREFIX` | `/wal-archive` | Lokales Archiv (Volume `pgarchive`). Gleicher Host wie die Datenbank: schützt vor logischem Verlust und Korruption, **nicht** vor dem Verlust der Maschine |
| `WALG_S3_PREFIX` + `AWS_ENDPOINT`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_FORCE_PATH_STYLE` | leer | S3-kompatibles Archiv (hat Vorrang vor dem Datei-Präfix); der Bucket muss bereits existieren. Der Dienst `minio` des Profils `audit-anchor` genügt für eine lokale Demo |
| `BACKUP_INTERVAL_SECONDS` / `BACKUP_RETAIN_FULL` | `86400` / `7` | Takt der Basis-Backups und Anzahl behaltener Voll-Backups |
| `BACKUP_PUSHGATEWAY_URL` | leer | Erhält nach jedem Backup `backup_last_success_timestamp` (die Metrik von `BackupStale`) |

Wiederherstellung und gemessene Werte: [DR-Runbook, Abschnitt 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path). Der S3-Modus wird über dieselben Variablen konfiguriert, wurde vom Drill aber nicht geübt (er nutzte das lokale Archiv).

### Helm-/Kubernetes-Bereitstellung — drei Datenbank-Varianten

Das Haupt-Chart deklariert, auf welche Variante sich ein Release stützt (`database.shape` in `deploy/helm/registerwerk/values.yaml`; das Chart verweigert Kombinationen, die `postgresql.enabled` widersprechen, und `NOTES.txt` nennt, was die Variante abdeckt und was nicht). Ziel der beiden Produktionsvarianten: **RPO ≤ 15 Minuten** durch kontinuierliche WAL-Archivierung (`archive_timeout` 300 s).

| `database.shape` | PostgreSQL | Wiederherstellungspunkt | Einsatz |
|---|---|---|---|
| `managed` | Verwalteter Dienst mit PITR (Cloud SQL über `cloudSqlProxy`, RDS, Azure Flexible Server, ...); `postgresql.enabled=false` | PITR des Anbieters (kontinuierliches Log-Archiv) | Produktion |
| `cnpg` | CloudNativePG-Cluster mit Barman-Cloud-WAL-Archivierung in Objektspeicher, außerhalb dieses Charts betrieben; `postgresql.enabled=false` | Kontinuierliches WAL-Archiv (`archive_timeout` 300 s) | Produktion |
| `bundled` (Standard) | Bitnami-Subchart, einzelner Primary | Letztes tägliches Basis-Backup (bis zu 24 h), kein PITR | Nur Dev/Test |

#### Verwaltetes PostgreSQL mit PITR (`database.shape: managed`)

Schalten Sie diese Punkte beim Anbieter ein; das Chart kann sie nicht prüfen, behandeln Sie sie als Abnahme-Checkliste:

- Point-in-Time-Recovery / kontinuierliche Archivierung der Transaktionslogs aktiviert, mit mindestens 7 Tagen Log-Aufbewahrung (Cloud SQL: `--enable-point-in-time-recovery --retained-transaction-log-days=7`; RDS: Aufbewahrung automatischer Backups von mindestens 7 Tagen; Azure Flexible Server: PITR-Aufbewahrung 7 bis 35 Tage).
- Automatische tägliche Backups mit genügend aufbewahrten Backups für Ihre Retention-Richtlinie, Hochverfügbarkeit (regional/Multi-AZ) und Löschschutz aktiviert.
- Setzen Sie `archive_mode`/`archive_command` nicht selbst; der Anbieter besitzt sie. Der RPO ist das vom Anbieter dokumentierte Log-Versandintervall (Wert im Vertrag/SLA bestätigen, auf das Sie sich stützen).
- Alarmieren Sie auf die eigene Archiv-/PITR-Gesundheitsmetrik des Anbieters und proben Sie einen echten Point-in-Time-Klon in eine Scratch-Instanz (`gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>` bei Cloud SQL), bevor Sie ihn brauchen. `scripts/pitr-drill.sh` steuert keinen verwalteten Dienst an.
- Den DML-only-Runtime-Login einmalig mit `postgres-init/roles/ensure-runtime-role.sh` anlegen.

#### CloudNativePG mit Barman Cloud (`database.shape: cnpg`)

`deploy/helm/registerwerk/examples/cnpg-cluster.yaml` ist ein vollständiges Beispiel: `ObjectStore` (S3-Ziel), ein `Cluster` mit 3 Instanzen und `archive_timeout: "300"`, das Barman-Cloud-Plugin als WAL-Archivierer, ein separates WAL-Volume, `enablePodMonitor` (liefert die `cnpg_pg_stat_archiver_*`-Metriken, die der Alert `WalArchiveLag` liest), ein tägliches `ScheduledBackup` und ein auskommentierter Recovery-`Cluster` mit `recoveryTarget.targetTime`. Installieren Sie zuerst Operator und Plugin, wenden Sie dann das Manifest an, setzen Sie `database.shape=cnpg` und `postgresql.enabled=false` und richten Sie `env.DB_URL` auf den `-rw`-Service. Das Manifest wurde in diesem Repository nicht auf einen laufenden Cluster angewendet; validieren Sie es gegen Ihre Operator- und Plugin-Versionen.

#### Gebündeltes PostgreSQL (`database.shape: bundled`, Dev/Test)

`deploy/helm/backup/` ist ein separates Helm-Chart (eigenes Release neben dem Haupt-Chart, nicht darin eingebunden), das täglich per WAL-G-`CronJob` ein **Basis-Backup** der gebündelten Datenbank nach S3 überträgt.

!!! warning "RPO ist das letzte tägliche Basis-Backup"
    Die gebündelte Datenbank archiviert kein WAL; der Wiederherstellungspunkt ist daher das letzte erfolgreiche Basis-Backup (bis zu 24 Stunden), Point-in-Time-Recovery ist nicht verfügbar. Ein fehlschlagendes `wal-g backup-push` lässt den Job fehlschlagen, ebenso ein fehlgeschlagener Pushgateway-Push, sodass `BackupStale` auslöst. Für das 15-Minuten-Ziel nutzen Sie `managed` oder `cnpg`.

Setzen Sie vor der Installation drei Chart-Werte, die es nicht selbst ableiten kann:

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0 \
  --set postgresql.existingSecret=<main chart's postgresql.auth.existingSecret>
```

Alle Optionen (S3-Bucket/Region, Aufbewahrung, IRSA vs. statische S3-Zugangsdaten, optionale Pushgateway-URL) siehe `deploy/helm/backup/values.yaml`. [pgBackRest](https://pgbackrest.org/) bleibt eine vernünftige Alternative zu WAL-G.

### Backups testen

**PITR-Drill (`scripts/pitr-drill.sh`).** Führt den gesamten WAL-G-Pfad auf Wegwerf-Containern und -Volumes aus (nie auf der Demo-Datenbank): Basis-Backup, WAL-Archiv, Totalverlust des Datenvolumens, Wiederherstellung auf einen festgehaltenen Zielzeitpunkt und zeilengenaue Prüfung. Es gibt die gemessene RPO-Stichprobe und die RTO aus und braucht bei Standard-`archive_timeout` etwa 6 Minuten. `--record <backend-base-url> <operator-bearer-token>` erfasst das Ergebnis als `SCENARIO_BASED`-Eintrag über `POST /api/v1/dora/resilience-tests` (standardmäßig aus). Letzter Lauf 2026-10-07: **PASSED**, RPO-Stichprobe **286.8 s** (Grenze: `archive_timeout` 300 s plus Upload-Zeit, Ziel ≤ 15 Min.), RTO **2.3 s** (davon `wal-g backup-fetch` 1.5 s) auf einer nahezu leeren Datenbank. Die RTO ist keine Zusage für die Produktion: Sie wächst mit der Größe des Basis-Backups und dem abzuspielenden WAL; wiederholen Sie den Drill (und messen Sie die Wiederherstellung) auf produktionsgroßen Daten.

```bash
scripts/pitr-drill.sh
scripts/pitr-drill.sh --record http://localhost:48080 "$OPERATOR_TOKEN"
```

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

### Point-in-Time-Wiederherstellung (WAL-G, CloudNativePG, verwaltet)

Die schrittweise PITR-Prozedur (PostgreSQL 18: `recovery.signal` und `recovery_target_time` in `postgresql.auto.conf`, keine `recovery.conf`) steht im [DR-Runbook, Abschnitt 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path). Für CloudNativePG nutzen Sie den Recovery-`Cluster` am Ende von `examples/cnpg-cluster.yaml`, für einen verwalteten Dienst den Point-in-Time-Klon des Anbieters. Die gebündelte Dev/Test-Datenbank lässt sich nur auf ihr letztes Basis-Backup wiederherstellen (`wal-g backup-fetch /bitnami/postgresql/data LATEST` aus einem Pod mit eingehängtem Daten-PVC, Backend vorher auf null skalieren).

### Wiederherstellungsziele

| Gegenstand | Wert | Grundlage |
|---|---|---|
| RPO, WAL-Archivierung aktiv | ≤ 5 Min. bei Schreibaktivität (`archive_timeout` 300 s + Upload); Ziel ≤ 15 Min. | Drill 2026-10-07 maß 286.8 s |
| RPO, gebündelte Dev/Test-Datenbank | Bis zu 24 h (letztes tägliches Basis-Backup) | Konstruktionsbedingt, kein WAL-Archiv |
| RTO, PITR-Wiederherstellung | 2.3 s auf der nahezu leeren Drill-Datenbank | Drill 2026-10-07; nicht auf produktionsgroßen Daten gemessen |
| RTO, kompletter Server-Neuaufbau | Nicht gemessen | Kein Drill; es wird keine Zahl zugesagt |
| Smart-Contract-Zustand | Die Wiederherstellung wird nicht vom Anwendungs-Backup gesteuert. Anwendungsprojektionen separat wiederherstellen und mit der konfigurierten Chain und dem instrumentspezifischen Rechtsregister abgleichen; die Blockchain ist nicht universell maßgeblich. |  |

## Backup-Überwachung

`BackupStale` löst jetzt aus, wenn das letzte Backup älter als 24 h ist **oder** die Serie fehlt; `BackupMetricAbsent` (Serie 2 Tage weg) und `BackupPushStale` ergänzen das. Ohne Backup-Dienst (Docker Compose) feuert `BackupStale` daher, weil die Serie fehlt.

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

Die WAL-Archivierung hat in derselben Datei eigene Alerts: `WalArchiveLag` (länger als 15 Minuten kein WAL-Segment archiviert, über 5 Minuten), `WalArchiveFailing` (`pg_stat_archiver.failed_count` wächst) und `WalArchiveMetricAbsent` (keine der Quellmetriken wird gescrapt, der Lag-Alert könnte also nie auslösen). Sie lesen eine von zwei Quellen:

- `cnpg_pg_stat_archiver_seconds_since_last_archival` und `cnpg_pg_stat_archiver_failed_count` aus dem integrierten CloudNativePG-Monitoring (`enablePodMonitor`; Namen gegen Ihre CNPG-Version prüfen).
- `pg_wal_archive_last_archive_age_seconds` und `pg_wal_archive_failed_count` aus einer benutzerdefinierten Abfrage auf `pg_stat_archiver` in Ihrem PostgreSQL-Exporter (z. B. die Queries-Datei von `postgres_exporter`; prüfen, ob Ihre Exporter-Version benutzerdefinierte Abfragen noch unterstützt):

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

Die Registry hat stets Schreibaktivität, daher bleibt das Archiv-Alter mit `archive_timeout` unter dem Timeout; ein Alter über 15 Minuten bedeutet, dass die Archivierung hängt, nicht dass die Datenbank ruht. Verwaltete Dienste liefern ein eigenes Äquivalent: an denselben Alert anbinden oder auf die Anbieter-Metrik alarmieren. `monitoring/alerts/tests/registerwerk.test.yml` testet diese Regeln per Unit-Test (`promtool test rules`).
