---
title: Backup e ripristino
---

# Backup e ripristino { #backups-and-recovery }

Lo stato persistente del registro risiede in due posti:
1. **PostgreSQL**: tutti i dati del registro (emissioni, entità, pista di controllo, KYC, stato dell'indicizzatore)
2. **S3/archiviazione di oggetti**: documenti KYC più grandi di 5 MB

Lo stato del contratto intelligente risiede sulla blockchain ed è intrinsecamente replicato: non è necessario eseguirne il backup separatamente.

## Strategia di backup PostgreSQL { #postgresql-backup-strategy }

I due percorsi di distribuzione documentati nel repository, in `CLAUDE.md`, utilizzano due meccanismi di backup diversi e **non
intercambiabili**. Segui la sezione che corrisponde al modo in cui stai effettivamente
eseguendo Registerwerk: in precedenza questa pagina descriveva solo il percorso Docker Compose, che
avrebbe indotto in errore un operatore che esegue la distribuzione Helm/Kubernetes (Fase 12, riscontro n. 6).

### Distribuzione Docker Compose — pg_dump manuale

Questa è la variante demo locale/a singolo host (vedere l'avviso «Not a production topology» in `CLAUDE.md`), non una simulazione di una vera strategia di backup: `docker compose up` non avvia alcun servizio di backup automatico. Le distribuzioni reali eseguono PostgreSQL come servizio gestito (Cloud SQL su GKE) con backup/PITR propri; vedere le forme di produzione più sotto. Dump manuale del database demo locale:

```bash
docker compose exec postgres pg_dump -U ${DB_USER:-registerwerk} --no-owner --no-privileges registerwerk \
  | gzip > registerwerk-$(date -u +%Y%m%d-%H%M%S).sql.gz
```

Per questo file locale non ci sono né retention pianificata né replica off-site: è un aiuto manuale una tantum, non una strategia di backup.

### Distribuzione Docker Compose — archiviazione WAL e PITR opzionali

Lo stack predefinito sopra resta invariato. L'overlay `docker-compose.wal.yml` è un'attivazione esplicita che aggiunge archiviazione WAL continua e ripristino a un punto nel tempo con [WAL-G](https://github.com/wal-g/wal-g):

```bash
# postgres with archive_mode=on (recreates the container; the pg18data volume is reused as is)
docker compose -f docker-compose.yml -f docker-compose.wal.yml up -d postgres
# daily base backups + retention (profile "backup"); one-shot variant: ... run --rm pg-backup once
docker compose -f docker-compose.yml -f docker-compose.wal.yml --profile backup up -d pg-backup
```

L'overlay sostituisce l'immagine con `registerwerk/postgres-wal:18.6` (`postgres-wal/`: la stessa base `postgres:18.6-alpine` più WAL-G compilato da un tag fissato, quindi un volume esistente non richiede dump/restore) e imposta `archive_mode=on`, `archive_command='wal-g wal-push %p'` e `archive_timeout`. Impostazioni (ambiente o `.env`):

| Variabile | Predefinito | Significato |
|---|---|---|
| `PG_ARCHIVE_TIMEOUT` | `300` | Secondi dopo i quali un server attivo deve cambiare segmento WAL e archiviarlo. È la manopola dell'RPO: l'RPO è limitato da questo valore più il tempo di upload |
| `WALG_FILE_PREFIX` | `/wal-archive` | Archivio locale (volume `pgarchive`). Stesso host del database: protegge da perdita logica e corruzione, **non** dalla perdita della macchina |
| `WALG_S3_PREFIX` + `AWS_ENDPOINT`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_FORCE_PATH_STYLE` | vuoto | Archivio compatibile S3 (prevale sul prefisso file); il bucket deve già esistere. Il servizio `minio` del profilo `audit-anchor` va bene per una demo locale |
| `BACKUP_INTERVAL_SECONDS` / `BACKUP_RETAIN_FULL` | `86400` / `7` | Cadenza dei backup base e numero di backup completi conservati |
| `BACKUP_PUSHGATEWAY_URL` | vuoto | Riceve `backup_last_success_timestamp` (la metrica di `BackupStale`) dopo ogni backup |

Ripristino e valori misurati: [runbook DR, sezione 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path). La modalità S3 si configura con le stesse variabili, ma il drill non l'ha esercitata (ha usato l'archivio locale).

### Distribuzione Helm/Kubernetes — tre forme di database

Il chart principale dichiara su quale forma si basa una release (`database.shape` in `deploy/helm/registerwerk/values.yaml`; il chart rifiuta combinazioni che contraddicono `postgresql.enabled`, e `NOTES.txt` indica cosa la forma copre e cosa no). Obiettivo delle due forme di produzione: **RPO ≤ 15 minuti** tramite archiviazione WAL continua (`archive_timeout` 300 s).

| `database.shape` | PostgreSQL | Punto di ripristino | Uso |
|---|---|---|---|
| `managed` | Servizio gestito con PITR (Cloud SQL tramite `cloudSqlProxy`, RDS, Azure Flexible Server, ...); `postgresql.enabled=false` | PITR del fornitore (archivio continuo dei log) | Produzione |
| `cnpg` | Cluster CloudNativePG con archiviazione WAL Barman Cloud su object storage, gestito fuori da questo chart; `postgresql.enabled=false` | Archivio WAL continuo (`archive_timeout` 300 s) | Produzione |
| `bundled` (predefinito) | Subchart Bitnami, singolo primary | Ultimo backup base giornaliero (fino a 24 h), nessun PITR | Solo dev/test |

#### PostgreSQL gestito con PITR (`database.shape: managed`)

Attivare questi punti presso il fornitore; il chart non può verificarli, trattarli come checklist di accettazione:

- Ripristino a un punto nel tempo / archiviazione continua dei log delle transazioni attivi, con almeno 7 giorni di retention dei log (Cloud SQL: `--enable-point-in-time-recovery --retained-transaction-log-days=7`; RDS: retention dei backup automatici di almeno 7 giorni; Azure Flexible Server: retention PITR da 7 a 35 giorni).
- Backup giornalieri automatici con un numero sufficiente di backup conservati per la propria politica di retention, alta disponibilità (regionale/multi-AZ) e protezione dall'eliminazione attive.
- Non impostare da soli `archive_mode`/`archive_command`; li gestisce il fornitore. L'RPO è l'intervallo di invio dei log documentato dal fornitore (confermare il valore nel contratto/SLA su cui ci si basa).
- Impostare avvisi sulla metrica di salute dell'archiviazione/PITR del fornitore e provare un vero clone a un punto nel tempo in un'istanza di prova (`gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>` su Cloud SQL) prima di averne bisogno. `scripts/pitr-drill.sh` non pilota un servizio gestito.
- Predisporre una sola volta il login runtime solo-DML con `postgres-init/roles/ensure-runtime-role.sh`.

#### CloudNativePG con Barman Cloud (`database.shape: cnpg`)

`deploy/helm/registerwerk/examples/cnpg-cluster.yaml` è un esempio completo: `ObjectStore` (destinazione S3), un `Cluster` da 3 istanze con `archive_timeout: "300"`, il plugin Barman Cloud come archiviatore WAL, un volume WAL separato, `enablePodMonitor` (espone le metriche `cnpg_pg_stat_archiver_*` lette dall'alert `WalArchiveLag`), un `ScheduledBackup` giornaliero e un `Cluster` di recupero commentato con `recoveryTarget.targetTime`. Installare prima operatore e plugin, poi applicare il manifest, impostare `database.shape=cnpg` e `postgresql.enabled=false` e puntare `env.DB_URL` al servizio `-rw`. Il manifest non è stato applicato a un cluster reale in questo repository; validarlo con le proprie versioni di operatore e plugin.

#### PostgreSQL integrato (`database.shape: bundled`, dev/test)

`deploy/helm/backup/` è un chart Helm separato (installato come release a sé, accanto al chart principale e non fuso con esso) che esegue un `CronJob` WAL-G giornaliero che invia un **backup base** del database integrato su S3.

!!! warning "L'RPO è l'ultimo backup base giornaliero"
    Il database integrato non archivia il WAL; il punto di ripristino è quindi l'ultimo backup base riuscito (fino a 24 ore) e il ripristino a un punto nel tempo non è disponibile. Un `wal-g backup-push` fallito fa fallire il job, così come un push Pushgateway fallito, quindi `BackupStale` scatta. Per l'obiettivo di 15 minuti usare `managed` o `cnpg`.

Prima dell'installazione impostare tre valori del chart che non può ricavare da solo:

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0 \
  --set postgresql.existingSecret=<main chart's postgresql.auth.existingSecret>
```

Per tutte le opzioni (bucket/regione S3, retention, IRSA o credenziali S3 statiche, URL Pushgateway facoltativo) vedere `deploy/helm/backup/values.yaml`. [pgBackRest](https://pgbackrest.org/) resta una ragionevole alternativa a WAL-G.

### Test dei backup { #testing-backups }

**Drill PITR (`scripts/pitr-drill.sh`).** Esegue l'intero percorso WAL-G su container e volumi usa e getta (mai sul database demo): backup base, archivio WAL, perdita totale del volume dati, ripristino a un orario obiettivo registrato e verifica riga per riga. Stampa il campione di RPO misurato e l'RTO e richiede circa 6 minuti con l'`archive_timeout` predefinito. `--record <backend-base-url> <operator-bearer-token>` registra il risultato come voce `SCENARIO_BASED` tramite `POST /api/v1/dora/resilience-tests` (disattivato per impostazione predefinita). Ultima esecuzione 2026-10-07: **PASSED**, campione di RPO **286.8 s** (limite: `archive_timeout` 300 s più tempo di upload, obiettivo ≤ 15 min), RTO **2.3 s** (di cui `wal-g backup-fetch` 1.5 s) su un database quasi vuoto. L'RTO non è una promessa per la produzione: cresce con la dimensione del backup base e con il WAL da riprodurre; ripetere il drill (e cronometrare il ripristino) su dati di dimensione produttiva.

```bash
scripts/pitr-drill.sh
scripts/pitr-drill.sh --record http://localhost:48080 "$OPERATOR_TOKEN"
```

Testa almeno la procedura di backup e ripristino mensile:

```bash
# Restore a backup to a test database
gunzip -c /backups/postgres/registerwerk-20250401-020000.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U registerwerk registerwerk_test
```

Verificare che le tabelle chiave siano presenti e che i conteggi dei dati siano ragionevoli:

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

## Backup dei documenti S3 { #s3-document-backup }

Abilita il controllo delle versioni S3 e la replica tra regioni per il tuo bucket di documenti KYC:

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

## Ripristino di emergenza { #disaster-recovery }

### Ripristino completo dal backup pg_dump (distribuzione Docker Compose) { #full-restore-from-pgdump-backup-docker-compose-deployment }

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

### Ripristino a un punto nel tempo (WAL-G, CloudNativePG, gestito)

La procedura PITR passo per passo (PostgreSQL 18: `recovery.signal` e `recovery_target_time` in `postgresql.auto.conf`, nessun `recovery.conf`) è nel [runbook DR, sezione 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path). Per CloudNativePG usare il `Cluster` di recupero in fondo a `examples/cnpg-cluster.yaml`; per un servizio gestito il clone a un punto nel tempo del fornitore. Il database integrato dev/test può essere ripristinato solo al suo ultimo backup base (`wal-g backup-fetch /bitnami/postgresql/data LATEST` da un pod con il PVC dei dati montato, portando prima il backend a zero repliche).

### Obiettivi di recupero

| Voce | Valore | Base |
|---|---|---|
| RPO, archiviazione WAL attiva | ≤ 5 min con attività di scrittura (`archive_timeout` 300 s + upload); obiettivo ≤ 15 min | Drill 2026-10-07: 286.8 s misurati |
| RPO, database integrato dev/test | Fino a 24 h (ultimo backup base giornaliero) | Per costruzione, nessun archivio WAL |
| RTO, ripristino PITR | 2.3 s sul database quasi vuoto del drill | Drill 2026-10-07; non misurato su dati di dimensione produttiva |
| RTO, ricostruzione completa del server | Non misurato | Nessun drill; nessun valore è promesso |
| Stato degli smart contract | Il ripristino non è controllato dal backup dell'applicazione. Recuperare separatamente le proiezioni applicative e riconciliarle con la chain configurata e con il registro giuridico specifico dello strumento; la blockchain non è universalmente determinante. |  |

## Monitoraggio del backup { #backup-monitoring }

`BackupStale` ora scatta se l'ultimo backup ha più di 24 h **oppure** la serie è assente; `BackupMetricAbsent` (serie assente da 2 giorni) e `BackupPushStale` lo completano. Senza servizio di backup (Docker Compose), `BackupStale` scatta perché la serie manca.

`monitoring/alerts/registerwerk.yml` include già una regola `BackupStale` che esegue l'interrogazione
`backup_last_success_timestamp`. Questa metrica esiste solo se qualcosa la invia effettivamente: un
CronJob è effimero e non può essere sottoposto a scraping direttamente, quindi entrambi i percorsi di backup la inviano a un Prometheus
Pushgateway in caso di successo:

- **Helm/Kubernetes (WAL-G)**: imposta `monitoring.pushgatewayUrl` in `deploy/helm/backup/values.yaml`
— il CronJob esegue il push automaticamente una volta impostato (vedi `templates/backup-cronjob.yaml`).
- **Docker Compose (pg_dump)**: aggiungi il push equivalente alla fine dello script cron:

  ```bash
  curl -s -X POST --data-binary "backup_last_success_timestamp $(date +%s)" \
    "$PUSHGATEWAY_URL/metrics/job/registerwerk_pg_backup"
  ```

Nessuno dei percorsi fornisce un Pushgateway per impostazione predefinita: aggiungine uno a qualsiasi stack di monitoraggio che stai
eseguendo (`monitoring/docker-compose.yml` per Compose o uno a livello di cluster per Kubernetes) se
vuoi che questo avviso abbia dati reali dietro di esso.

L'archiviazione WAL ha propri alert nello stesso file: `WalArchiveLag` (nessun segmento WAL archiviato da oltre 15 minuti, per 5 minuti), `WalArchiveFailing` (`pg_stat_archiver.failed_count` cresce) e `WalArchiveMetricAbsent` (nessuna metrica sorgente viene raccolta, quindi l'alert di ritardo non potrebbe mai scattare). Leggono una di due sorgenti:

- `cnpg_pg_stat_archiver_seconds_since_last_archival` e `cnpg_pg_stat_archiver_failed_count` dal monitoraggio integrato di CloudNativePG (`enablePodMonitor`; verificare i nomi con la propria versione di CNPG).
- `pg_wal_archive_last_archive_age_seconds` e `pg_wal_archive_failed_count` da una query personalizzata su `pg_stat_archiver` nel proprio exporter PostgreSQL (ad es. il file di query di `postgres_exporter`; verificare che la versione supporti ancora le query personalizzate):

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

Il registro ha sempre attività di scrittura, quindi con `archive_timeout` l'età dell'archivio resta sotto il timeout; un'età superiore a 15 minuti significa che l'archiviazione è bloccata, non che il database sia inattivo. I servizi gestiti espongono un proprio equivalente: collegarlo allo stesso alert o impostare avvisi sulla metrica del fornitore. `monitoring/alerts/tests/registerwerk.test.yml` testa queste regole con test unitari (`promtool test rules`).
