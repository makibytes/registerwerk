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
**Obiettivo RTO:** ≤4 ore (eWpRV §6)  
**Obiettivo RPO:** ≤15 minuti (archiviazione WAL tramite wal-g)  
**Responsabile:** Registry Operations Team  
**Classificazione DORA:** incidente MAJOR se il downtime supera le 4 ore

---

## 1. Classificazione della gravità degli incidenti (DORA, art. 17)

| Gravità | Criteri | Azione | Termine |
|---|---|---|---|
| MINOR | Singolo servizio non disponibile, &lt;30 min, nessuna perdita di dati | Allerta interna | — |
| MAJOR | Più servizi coinvolti, 30 min–4h, potenziale impatto sui dati | Segnalazione iniziale a BaFin/CSSF/AMF/FMA | 72h dal rilevamento |
| CRITICAL | Interruzione totale >4h OPPURE violazione dell'integrità dei dati | Segnalazione iniziale all'autorità competente | 4h dal rilevamento |

`POST /api/v1/dora/incidents` registra un incidente interno; non presenta una segnalazione DORA. Qualsiasi
autorità, termine, modulo e canale indicati di seguito costituiscono un elemento da verificare esternamente:
- DE: BaFin (bafin.de), Referat IT-Risikoaufsicht
- LU: CSSF tramite portale CSS
- FR: AMF / ACPR tramite ONEGATE
- LI: FMA tramite portale LIMA

---

## 2. Ripristino completo di Postgres (RPO ≤15 min)

### 2a. Ripristino dal backup wal-g (percorso primario)
```bash
# 1. Effettuare il provisioning di una nuova istanza Postgres 17
docker run -d --name postgres-restore postgres:17-alpine

# 2. Ripristinare il backup di base
docker exec postgres-restore wal-g backup-fetch /var/lib/postgresql/data LATEST \
  --walg-s3-prefix s3://registerwerk-backups/wal-g

# 3. Riapplicare i WAL fino al momento di destinazione
cat > /tmp/recovery.conf << EOF
restore_command = 'wal-g wal-fetch "%f" "%p"'
recovery_target_time = '$(date -u -d "-15 minutes" +"%Y-%m-%d %H:%M:%S")'
recovery_target_action = 'promote'
EOF

# 4. Avviare Postgres e attendere il completamento del recovery
docker start postgres-restore
docker logs -f postgres-restore | grep "recovery is complete"

# 5. Convalidare il conteggio delle righe
psql -h localhost -U registerwerk -c "SELECT count(*) FROM audit_event;"
psql -h localhost -U registerwerk -c "SELECT max(occurred_at) FROM audit_event;"
```

### 2b. Ripristino da pg_dump (fallback — RPO = ultimo dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

---

## 3. Ripristino del backend
```bash
# Scaricare l'immagine firmata (verificare prima la firma Cosign)
cosign verify ghcr.io/makibytes/registerwerk/backend:VERSION

# Effettuare il deploy con l'ambiente di produzione
docker run -d \
  --env-file /etc/registerwerk/prod.env \
  -e REGISTERWERK_PRODUCTION_MODE=true \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/makibytes/registerwerk/backend:VERSION

# Verificare lo stato di salute
curl http://localhost:8080/actuator/health | jq .status
```

---

## 4. Verifica della catena di controllo dopo il ripristino
```bash
# Attivare la verifica della catena di controllo tramite l'actuator
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/actuator/health/auditChainVerificationService | jq .

# Se BROKEN: non riprendere le operazioni. Escalare a incidente DORA CRITICAL.
# La violazione della catena di hash deve essere indagata prima che il registro riprenda l'attività.
```

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
```bash
deck gateway sync gateway/kong.yml \
  --kong-addr http://localhost:8001
```

---

## 7. Checklist post-ripristino
- [ ] Stato di Postgres: `pg_isready`
- [ ] Stato del backend: `/actuator/health` → UP
- [ ] Catena di controllo: `/actuator/health/auditChainVerificationService` → UP
- [ ] Attività dell'indicizzatore: `/actuator/health/indexerMonitor` → UP
- [ ] Deriva della catena (chain drift): confermare l'assenza di righe `chain_drift_event` aperte con severity=CRITICAL
- [ ] Screening sanzioni: confermare l'assenza di righe `screening_hit` aperte da più di 4h
- [ ] Panoramica del registro: verificare che gli importi nominali totali corrispondano all'istantanea pre-incidente
- [ ] Presentare il rapporto finale DORA entro 1 mese dalla risoluzione dell'incidente
