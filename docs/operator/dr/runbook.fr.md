---
title: Runbook de reprise après sinistre
description: Runbook opérationnel provisoire pour la restauration de Postgres et du backend, la vérification de la chaîne d'audit et la classification des incidents DORA — en attente d'approbation et de test par l'opérateur.
---

# Runbook de reprise après sinistre

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

## 1. Classification de la gravité des incidents (DORA Art. 17)

| Gravité | Critères | Action | Délai |
|---|---|---|---|
| MINOR | Panne d'un seul service, &lt;30 min, aucune perte de données | Alerte interne | — |
| MAJOR | Multi-services, 30 min–4h, impact potentiel sur les données | Rapport initial à BaFin/CSSF/AMF/FMA | 72h à compter de la détection |
| CRITICAL | Panne totale >4h OU atteinte à l'intégrité des données | Rapport initial à l'autorité compétente | 4h à compter de la détection |

`POST /api/v1/dora/incidents` records an internal incident; it does not file a DORA report. Any
authority, deadline, form, and channel below is a review input that must be verified externally:
- DE: BaFin (bafin.de) Referat IT-Risikoaufsicht
- LU: CSSF via CSS portal
- FR: AMF / ACPR via ONEGATE
- LI: FMA via LIMA portal

---

## 2. Restauration complète de Postgres (RPO ≤15 min)

### 2a. Restaurer à partir de la sauvegarde wal-g (chemin principal)
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

### 2b. Restaurer à partir de pg_dump (repli – RPO = dernier dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

---

## 3. Restauration du backend
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

## 4. Vérification de la chaîne d'audit après restauration
```bash
# Trigger audit chain verification via actuator
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/actuator/health/auditChainVerificationService | jq .

# If BROKEN: do NOT resume operations. Escalate to DORA CRITICAL incident.
# The hash chain violation must be investigated before the registry resumes.
```

---

## 5. Matériau de clé Break-Glass (remplace le point de terminaison exportRaw supprimé)

L'accès brut à la clé privée nécessite les trois éléments suivants :
1. Deux parts Shamir sur trois (détenues par le CTO, le CFO et un conseil juridique externe)
2. Résolution du conseil d'administration (préavis minimum de 24 heures au conseil juridique réglementaire)
3. Dossier de bris de glace approuvé et audité en interne. Toute notification à un régulateur est spécifique à l'incident, à l'opérateur et à la juridiction, et doit suivre la procédure approuvée en externe ; ce dépôt de code ne la transmet à aucune autorité.

Accès d'urgence au KMS (AWS KMS) :
```bash
aws kms decrypt \
  --ciphertext-blob fileb://wallet-wrapped-dek.bin \
  --key-id arn:aws:kms:eu-central-1:ACCT:key/KEY_ID \
  --output text --query Plaintext | base64 -d > dek.bin
```

---

## 6. Restauration de Kong/passerelle
```bash
deck gateway sync gateway/kong.yml \
  --kong-addr http://localhost:48001
```

---

## 7. Liste de contrôle post-récupération
- [ ] État de Postgres : `pg_isready`
- [ ] État du backend : `/actuator/health` → UP
- [ ] Chaîne d'audit : `/actuator/health/auditChainVerificationService` → UP
- [ ] Vivacité de l'indexeur : `/actuator/health/indexerMonitor` → UP
- [ ] Dérive de la chaîne : confirmer qu'aucune ligne `chain_drift_event` ouverte n'a une gravité = CRITICAL
- [ ] Vérification des sanctions : confirmer qu'aucune ligne `screening_hit` ouverte n'a plus de 4 h
- [ ] Aperçu du registre : vérifier que les montants nominaux totaux correspondent à l'instantané pré-incident
- [ ] Déposer le rapport final du DORA dans le mois suivant la résolution de l'incident
