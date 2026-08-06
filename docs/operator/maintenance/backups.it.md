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

### Distribuzione Docker Compose — pg_dump { #docker-compose-deployment-pgdump }

Aggiungi questo processo cron al tuo server (o eseguilo come contenitore Docker):

```bash
# /etc/cron.d/ewpg-backup
0 2 * * * root docker exec registerwerk-postgres-1 \
  pg_dump -U registerwerk registerwerk | gzip \
  > /backups/postgres/registerwerk-$(date +%Y%m%d-%H%M%S).sql.gz
```

Conserva i backup giornalieri per 30 giorni, backup settimanali per 12 mesi:

```bash
# Delete daily backups older than 30 days
find /backups/postgres -name "*.sql.gz" -mtime +30 -delete
```

### Distribuzione Helm/Kubernetes: archiviazione continua WAL-G { #helmkubernetes-deployment-wal-g-continuous-archiving }

L'approccio pg_dump di Docker Compose sopra **non** si applica qui. `deploy/helm/backup/` è un chart Helm separato
(installato come release a sé stante, accanto al — non unito al — chart principale
`deploy/helm/registerwerk`) che esegue un WAL-G `CronJob` quotidiano che archivia backup continui
su S3. Prima di installarlo, è necessario impostare due valori del chart che non può derivare da solo:

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0
```

Vedi `deploy/helm/backup/values.yaml` per il set completo di opzioni (bucket/regione S3, conservazione,
IRSA rispetto a credenziali S3 statiche, URL opzionale del Prometheus Pushgateway per la metrica di obsolescenza
di seguito).

Per entrambi i percorsi, [pgBackRest](https://pgbackrest.org/) o [Barman](https://pgbarman.org/) restano
alternative ragionevoli se preferisci non eseguire WAL-G direttamente.

### Test dei backup { #testing-backups }

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

### Ripristino dal backup WAL-G (distribuzione Helm/Kubernetes) { #restore-from-wal-g-backup-helmkubernetes-deployment }

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

### Obiettivi tempi di recupero { #recovery-time-objectives }

| Scenario | RTO (Obiettivo tempo di recupero) |
|----------|------------------------------|
| Riavvio del servizio singolo | < 1 minuto |
| Ripristino del database dal backup giornaliero | < 30 minuti |
| Ricostruzione completa del server da zero | < 4 ore |
| Stato del contratto intelligente | Il ripristino non è controllato dal backup dell'applicazione. Recuperare separatamente le proiezioni applicative e riconciliarle con la catena configurata e con il registro giuridicamente rilevante specifico dello strumento; la blockchain non è universalmente autorevole. |

## Monitoraggio del backup { #backup-monitoring }

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
