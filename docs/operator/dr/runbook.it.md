---
title: Runbook operativo per il disaster recovery
description: Bozza di runbook operativo per il ripristino di Postgres e del backend, la verifica della catena di controllo e la classificazione degli incidenti DORA — in attesa di approvazione e collaudo da parte dell'operatore.
---

# Runbook di disaster recovery

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questo è un runbook operativo in bozza, non la prova di un piano di continuità approvato, di RTO/RPO
    collaudati, di una classificazione degli incidenti giuridicamente corretta o di una notifica alle autorità.
    L'operatore deve approvare, testare e allineare questo runbook ai requisiti legali, regolamentari,
    contrattuali e infrastrutturali attualmente vigenti.

**Servizio:** Registro eWpG Registerwerk  
**RTO:** misurato 2.3 s sul database quasi vuoto del drill (2026-10-07, `scripts/pitr-drill.sh`); non misurato su dati di dimensione produttiva, quindi non viene promesso alcun RTO  
**RPO:** obiettivo ≤ 15 minuti con l'archiviazione WAL attiva (`archive_timeout` 300 s); l'ultimo drill (2026-10-07) ha misurato 286.8 s tra l'ultima riga confermata e il suo segmento WAL archiviato. Senza archiviazione WAL (database integrato dev/test) l'RPO è l'ultimo backup base giornaliero, fino a 24 ore  
**Responsabile:** Registry Operations Team  
**Classificazione DORA:** vedere la tabella illustrativa nella sezione 1

---

## 1. Classificazione della gravità degli incidenti (DORA, art. 17)

| Gravità | Criteri | Azione |
|---|---|---|
| MINOR | Singolo servizio non disponibile, nessuna perdita di dati | Avviso interno |
| MAJOR | Interruzione di più servizi, possibile impatto sui dati | Valutare l'obbligo di segnalazione ai sensi dell'art. 19 DORA |
| CRITICAL | Interruzione totale O violazione dell'integrità dei dati | Valutare l'obbligo di segnalazione ai sensi dell'art. 19 DORA |

Questa tabella è una classificazione illustrativa, non una determinazione giuridica. Verificarla rispetto all'art. 19 DORA e
agli atti delegati sulla classificazione degli incidenti (regolamento delegato (UE) 2024/1772) e su contenuto e termini delle
segnalazioni (regolamento delegato (UE) 2025/301). Le soglie di durata e i termini di segnalazione non sono volutamente fissati
qui: sono la politica propria dell'operatore (decisione rinviata T9-06) e i termini legali decorrono dai momenti definiti da
quegli atti, non dal «rilevamento».

`POST /api/v1/dora/incidents` registra un incidente interno; non presenta una segnalazione DORA. Qualsiasi
autorità, termine, modulo e canale indicati di seguito costituiscono un elemento da verificare esternamente:
- DE: BaFin (bafin.de), Referat IT-Risikoaufsicht
- LU: CSSF tramite portale CSS
- FR: AMF / ACPR tramite ONEGATE
- LI: FMA tramite portale LIMA

---

## 2. Ripristino completo di Postgres (PITR con WAL-G, RPO = ultimo segmento WAL archiviato)

!!! note "Provato davvero"
    La sezione 2a viene esercitata da `scripts/pitr-drill.sh` su container usa e getta (mai sul database demo): backup base, archivio WAL continuo, perdita totale del volume dati, ripristino a un orario obiettivo scelto, verifica riga per riga. Ultima esecuzione 2026-10-07: PASSED, campione di RPO 286.8 s, RTO 2.3 s (di cui `wal-g backup-fetch` 1.5 s) su un database quasi vuoto. L'RTO cresce con la dimensione del backup base e con il WAL da riprodurre; rimisurarlo su dati di dimensione produttiva prima di assumere impegni.

!!! warning "Solo con l'archiviazione WAL attiva"
    Il ripristino a un punto nel tempo richiede un archivio WAL: l'overlay opzionale `docker-compose.wal.yml` (WAL-G), un PostgreSQL gestito con PITR oppure CloudNativePG con Barman Cloud (vedere [Backup e ripristino](../maintenance/backups.md)). Il database integrato dev/test e la demo Compose standard non archiviano nulla; lì il punto di ripristino è l'ultimo backup base o pg_dump.

### 2a. Ripristino a un punto nel tempo da WAL-G (percorso primario) { #2a-point-in-time-restore-from-wal-g-primary-path }

Meccanica di PostgreSQL 18: `PGDATA` è `/var/lib/postgresql/18/docker` sull'immagine ufficiale (che dichiara `VOLUME /var/lib/postgresql`), il recupero si richiede con un file `recovery.signal` vuoto e l'obiettivo si imposta in `postgresql.auto.conf`. Non esiste più `recovery.conf`.

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

Con storage S3 sostituire i mount `-v ..._pgarchive` con le variabili `WALG_S3_PREFIX` / `AWS_*` del server di origine. Dopo la promozione l'istanza gira su una nuova timeline: eseguire subito un nuovo backup base (`pg-backup once`). Per le forme di produzione usare il PITR della piattaforma: PostgreSQL gestito (ad es. `gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>`) o un cluster di recupero CloudNativePG (`deploy/helm/registerwerk/examples/cnpg-cluster.yaml`, in fondo). Questi due percorsi non sono esercitati dallo script di questo repository; eseguire un proprio drill di ripristino.

I dati confermati dopo l'obiettivo di ripristino (o dopo l'ultimo segmento archiviato) vanno persi. Confrontare `max(occurred_at)` con l'orario dell'incidente per quantificare la perdita effettiva, poi riconciliare con gli indexer di chain.

### 2b. Ripristino da pg_dump (fallback — RPO = ultimo dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

**`scripts/dr-restore-drill.sh` automatizza solo questo percorso di fallback con pg_dump (`scripts/pitr-drill.sh` copre il percorso WAL-G di 2a)** (pg_dump del servizio compose `postgres` in esecuzione → ripristino in un container usa e getta → confronto del numero di righe di ogni tabella → riporto di un valore di RTO) e, con `--record-dora <backend-base-url> <bearer-token>`, registra l'esito come una vera voce `SCENARIO_BASED` in `POST /api/v1/dora/resilience-tests` — un'esercitazione di continuità realmente eseguita da un operatore, non un segnaposto popolato dalla demo. Non esercita deliberatamente 2a, che ha la propria esercitazione (`scripts/pitr-drill.sh`, sotto). Eseguilo periodicamente (ad es. ogni trimestre) e dopo ogni modifica dello schema che tocca le migrazioni, per mantenere aggiornato il «testata» di «continuità testata».

Con `--verify-audit-chain` automatizza inoltre la metà della catena hash di audit di questa sezione: avvia un vero container backend usa e getta sulla copia ripristinata (HSM disattivato solo per quel container usa e getta — non gli serve alcun meccanismo di wallet/firma, solo la connettività al database — così l'opzione funziona con in esecuzione il solo servizio `postgres`, non l'intero stack demo) e chiama il `POST /api/v1/audit/chain/verify` dell'applicazione stessa, invece di reimplementare in bash la canonicalizzazione SHA-256 di `AuditChainVerificationService`, che rischierebbe di divergere in silenzio dando una falsa sicurezza. Richiede che `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` siano disponibili (ambiente della shell o `.env` nella radice del repository) e corrispondano alle credenziali con cui il database *sorgente* è stato effettivamente popolato; senza di esse questo passo è segnalato `SKIPPED`, non trattato come un fallimento dell'esercitazione.

**`scripts/pitr-drill.sh` esercita il percorso WAL-G (2a).** Costruisce la stessa immagine `postgres-wal` dell'overlay Compose, avvia un server usa e getta con `archive_mode=on`, esegue un backup base, inserisce righe prima e dopo un orario obiettivo registrato, attende senza forzare un cambio di segmento che il segmento dell'ultima riga sia archiviato (il campione di RPO), elimina il container e il suo volume, ripristina backup base più WAL all'orario obiettivo e verifica che esistano esattamente le righe confermate fino ad allora. Stampa RPO e RTO e dura circa 6 minuti con `archive_timeout` 300 s. Con `--record <backend-base-url> <operator-bearer-token>` registra il risultato come voce `SCENARIO_BASED` tramite `POST /api/v1/dora/resilience-tests` (disattivato per impostazione predefinita).

### 2c. Promozione della replica in lettura (Helm/Kubernetes, solo `values-production.yaml`)

`values-production.yaml` esegue facoltativamente una replica Postgres in lettura in streaming accanto al primario (`postgresql.architecture: replication`) — uno standby a caldo in tempo reale, non un backup periodico. **Non** è un failover automatico: nulla ripunta il `DB_URL` del backend alla replica se il primario muore, e la replica resta in sola lettura finché non viene promossa esplicitamente. Se il primario è perso e la replica è integra, è più veloce di un ripristino WAL-G completo (§2a):

```bash
# 1. Confermare che il ritardo di replica della replica sia abbastanza basso da accettare la perdita di dati
kubectl exec -it <release-name>-postgresql-read-0 -- \
  psql -U postgres -c "SELECT now() - pg_last_xact_replay_timestamp() AS replication_lag;"

# 2. Promuovere la replica fuori dalla modalità di recovery
kubectl exec -it <release-name>-postgresql-read-0 -- pg_ctl promote -D /bitnami/postgresql/data

# 3. Puntare il backend all'istanza promossa
kubectl set env deployment/<release-name> \
  DB_URL="jdbc:postgresql://<release-name>-postgresql-read:5432/registerwerk"

# 4. Quando il vecchio primario è recuperabile, ricostruiscilo come nuova replica (NON lasciare che
#    rientri come primario — lui e l'istanza promossa ormai sono divergenti) oppure esegui un nuovo
#    `helm upgrade` perché il subchart ricrei da zero la topologia primario/replica.
```

Ogni transazione non ancora trasmessa alla replica al momento della promozione va persa — è un RPO reale, non zero; come per il percorso WAL-G sopra, l'RPO è limitato da ciò che è stato catturato per ultimo (qui: dal ritardo di replica, non dall'archivio). Esercita questa promozione in un namespace non di produzione prima di farvi affidamento durante un incidente reale.

### 2d. Righe in una partizione DEFAULT

`token_transfer`, `blockchain_transaction` e `audit_event` sono partizionate per mese. Una riga con `occurred_at` fuori dalle partizioni esistenti (sorgente dell'indexer con orologio errato, backfill storico) finisce in `<tabella>_default`, e Postgres non può più creare la partizione di quel mese. Scatta l'alert `PartitionDefaultRowsPresent` (`registerwerk_partition_default_rows`). Dividere la partizione di default in una finestra di manutenzione (tabella, mese e date sono esempi):

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

Ripetere per ogni mese e tabella interessati. Non esiste un job di divisione automatica (richiede una decisione sulla finestra di manutenzione).

---

## 3. Ripristino del backend
```bash
# Scaricare l'immagine firmata (verificare prima la firma Cosign)
cosign verify ghcr.io/makibytes/registerwerk/backend:VERSION

# Effettuare il deploy con l'ambiente di produzione
docker run -d \
  --env-file /etc/registerwerk/prod.env \
  -e REGISTERWERK_PRODUCTION_MODE=true \
  -p 127.0.0.1:48080:8080 \
  ghcr.io/makibytes/registerwerk/backend:VERSION

# Verificare lo stato di salute
curl http://localhost:48080/actuator/health | jq .status
```

---

## 4. Verifica della catena di controllo dopo il ripristino
```bash
# Trigger the verification: a POST (reading /actuator/health/auditChainVerificationService only
# reports the last verdict, it triggers nothing). Requires REGISTRY_ADMIN; scripts/dr-restore-drill.sh
# --verify-audit-chain shows the full login + XSRF-TOKEN flow.
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/api/v1/audit/chain/verify | jq .
```

Se il verdetto è BROKEN: NON riprendere le operazioni. Escalare come incidente CRITICAL; la rottura della catena di hash va indagata prima che il registro riprenda. Un verdetto BROKEN mantiene `/actuator/health` su DOWN (la readiness non è influenzata) finché un'esecuzione SUCCESSIVA non è valida E il verdetto errato non è stato riconosciuto con doppio controllo: `POST /api/v1/audit/verification/{id}/ack` (REGISTRY_ADMIN, step-up, un secondo approvatore, motivo `AUDIT_CHAIN_VERIFICATION_ACK`, `note` facoltativa); la pagina del registro di audit del portale operatore ha il pulsante corrispondente. L'avvio è un POST; leggere `/actuator/health/auditChainVerificationService` mostra solo l'ultimo verdetto.

---

## 5. Accesso di emergenza al materiale chiave — break-glass (sostituisce l'endpoint exportRaw rimosso)

L'accesso diretto (raw) alla chiave privata richiede tutti e tre i seguenti elementi:
1. Due quote su tre dello schema di condivisione Shamir (Shamir shares — detenute da CTO, CFO e legale esterno)
2. Delibera del consiglio di amministrazione (preavviso minimo di 24 ore al legale per le questioni regolamentari)
3. Verbale di accesso di emergenza (break-glass) approvato e verificato internamente. Qualsiasi notifica alle
   autorità di regolamentazione dipende dall'incidente, dall'operatore e dalla giurisdizione e deve seguire la
   procedura approvata esternamente; questo repository non la presenta ad alcuna autorità.

Accesso di emergenza al KMS (AWS KMS):
```bash
aws kms decrypt \
  --ciphertext-blob fileb://wallet-wrapped-dek.bin \
  --key-id arn:aws:kms:eu-central-1:ACCT:key/KEY_ID \
  --output text --query Plaintext | base64 -d > dek.bin
```

---

## 6. Ripristino di Kong/Gateway
Kong funziona senza database (DB-less): l'intera configurazione è `gateway/kong.yml`, caricata all'avvio (`KONG_DECLARATIVE_CONFIG`). Non c'è alcun database da ripristinare né un percorso di scrittura tramite l'Admin API, quindi `deck sync` non si applica. Ripristinare il file dal controllo di versione, validarlo e ricreare il container:
```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml
docker compose up -d --force-recreate kong
```
Su Kubernetes lo stesso file è fornito come `deploy/helm/registerwerk/files/kong.yml` (ConfigMap `registerwerk-kong-config`): eseguire `helm upgrade` e rilasciare di nuovo Kong.

---

## 7. Checklist post-ripristino
- [ ] Stato di Postgres: `pg_isready`
- [ ] Stato del backend: `/actuator/health` → UP
- [ ] Catena di audit: `POST /api/v1/audit/chain/verify` restituisce `valid: true` (sezione 4), poi `/actuator/health` → UP
- [ ] Attività degli indexer: `GET /api/v1/indexers` mostra tutti gli indexer aggiornati e nessun alert `IndexerStaleCritical` / `IndexerStaleWarning` è attivo
- [ ] Deriva della catena (chain drift): confermare l'assenza di righe `chain_drift_event` aperte con severity=CRITICAL
- [ ] Screening sanzioni: confermare l'assenza di righe `screening_hit` aperte da più di 4h
- [ ] Panoramica del registro: verificare che gli importi nominali totali corrispondano all'istantanea pre-incidente
- [ ] Se l'incidente è stato classificato come grave, presentare le segnalazioni DORA entro i termini dell'atto delegato (verificare il testo vigente, sezione 1)

## 8. Esercitazioni di caos (Docker Compose)

Due script esercitano un reale comportamento di guasto/recupero sullo stack Compose in esecuzione — non è una simulazione — e, per `chaos-drill.sh`, registrano l'esito come una vera riga DORA `ResilienceTest`, lo stesso meccanismo che `dr-restore-drill.sh --record-dora` già usa. Entrambi riavviano ciò che hanno terminato prima di uscire, ma aspettati un breve riavvio del backend; non eseguirli su uno stack che altri stanno usando attivamente.

```bash
scripts/chaos-drill.sh kill-postgres   # SIGKILL a postgres in pieno traffico; misurare degrado + recupero
scripts/chaos-drill.sh kill-backend    # SIGKILL al backend a metà richiesta; misurare il recupero
scripts/verify-graceful-shutdown.sh    # docker stop (SIGTERM) in contrasto con quanto sopra — il caso di contrasto
```

**`kill-backend` ha trovato una lacuna reale e finora non documentata la prima volta che è stato eseguito**: la policy Docker `restart: unless-stopped` **non** riavvia un container dopo `docker kill` o `docker stop` — confermato dal fatto che `docker inspect ... RestartCount` è rimasto a 0 dopo l'uccisione. Recupera solo da un vero crash nel processo osservato dal runtime dei container stesso, non da una terminazione avviata tramite l'API del motore. `kill-backend` ora lo misura onestamente: attende 20 s per il recupero automatico e, se non avviene, ripiega su un `docker start` esplicito e registra l'esito come `FINDINGS_OPEN`, non `PASSED`. Il percorso Helm/Kubernetes non condivide questa lacuna — `restartPolicy: Always` (il valore implicito di un Deployment) riavvia un pod dopo *qualsiasi* uscita del container, amministrativa o no. Se l'autoguarigione dopo l'uccisione di un container conta specificamente per il percorso Compose, è un vero seguito da affrontare (un supervisore esterno, o accettare il recupero manuale come modello documentato), non qualcosa che uno dei due script nasconda.

`verify-graceful-shutdown.sh` è il caso di contrasto: invia una raffica di richieste concorrenti, manda un vero `docker stop` (SIGTERM) a metà raffica e conferma che il container termina da solo entro il proprio `stop_grace_period` (35 s, `docker-compose.yml` — allineato a `spring.lifecycle.timeout-per-shutdown-phase`, `application.yml`) invece di essere forzato dall'escalation SIGKILL di Docker, e che le richieste in corso si completano invece di essere reimpostate. È a questo che serve davvero `server.shutdown: graceful` — un normale stop/ricreazione, non un crash — ed è il motivo per cui il servizio backend di `docker-compose.yml` ha bisogno di un `stop_grace_period` esplicito: il timeout di stop predefinito di Docker (10 s) è più breve dei 30 s che l'applicazione usa per il proprio drenaggio.
