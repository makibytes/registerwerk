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
**RTO :** mesuré 2.3 s sur la base quasi vide de l'exercice (2026-10-07, `scripts/pitr-drill.sh`) ; non mesuré sur des données de taille production, donc aucun RTO n'est promis  
**RPO :** objectif ≤ 15 minutes avec l'archivage WAL activé (`archive_timeout` 300 s) ; le dernier exercice (2026-10-07) a mesuré 286.8 s entre la dernière ligne validée et son segment WAL archivé. Sans archivage WAL (base intégrée dev/test), le RPO est la dernière sauvegarde de base quotidienne, jusqu'à 24 heures  
**Owner:** Registry Operations Team  
**Classification DORA :** voir le tableau illustratif de la section 1

---

## 1. Classification de la gravité des incidents (DORA Art. 17)

| Gravité | Critères | Action |
|---|---|---|
| MINOR | Service unique indisponible, aucune perte de données | Alerte interne |
| MAJOR | Panne multi-services, impact potentiel sur les données | Évaluer l'obligation de déclaration au titre de l'art. 19 DORA |
| CRITICAL | Panne totale OU atteinte à l'intégrité des données | Évaluer l'obligation de déclaration au titre de l'art. 19 DORA |

Ce tableau est une classification illustrative, pas une qualification juridique. Vérifiez-le au regard de l'art. 19 DORA et
des actes délégués sur la classification des incidents (règlement délégué (UE) 2024/1772) et sur le contenu et les délais des
déclarations (règlement délégué (UE) 2025/301). Les seuils de durée et les délais de déclaration ne sont volontairement pas figés
ici : ils relèvent de la politique de l'opérateur (décision reportée T9-06), et les délais légaux courent à partir des moments
que ces actes définissent, non à partir de la « détection ».

`POST /api/v1/dora/incidents` records an internal incident; it does not file a DORA report. Any
authority, deadline, form, and channel below is a review input that must be verified externally:
- DE: BaFin (bafin.de) Referat IT-Risikoaufsicht
- LU: CSSF via CSS portal
- FR: AMF / ACPR via ONEGATE
- LI: FMA via LIMA portal

---

## 2. Restauration complète de Postgres (PITR WAL-G, RPO = dernier segment WAL archivé)

!!! note "Exercé pour de vrai"
    La section 2a est exercée par `scripts/pitr-drill.sh` sur des conteneurs jetables (jamais sur la base de démonstration) : sauvegarde de base, archive WAL continue, perte totale du volume de données, restauration à une heure cible choisie, vérification ligne à ligne. Dernier passage 2026-10-07 : PASSED, échantillon de RPO 286.8 s, RTO 2.3 s (dont `wal-g backup-fetch` 1.5 s) sur une base quasi vide. Le RTO croît avec la taille de la sauvegarde de base et le WAL à rejouer ; à re-mesurer sur des données de taille production avant tout engagement.

!!! warning "Uniquement avec l'archivage WAL activé"
    La restauration à un instant donné exige une archive WAL : le overlay optionnel `docker-compose.wal.yml` (WAL-G), un PostgreSQL géré avec PITR, ou CloudNativePG avec Barman Cloud (voir [Sauvegardes et récupération](../maintenance/backups.md)). La base intégrée dev/test et la démo Compose standard n'archivent rien ; le point de restauration y est la dernière sauvegarde de base ou le dernier pg_dump.

### 2a. Restauration à un instant donné depuis WAL-G (chemin principal) { #2a-point-in-time-restore-from-wal-g-primary-path }

Mécanique PostgreSQL 18 : `PGDATA` vaut `/var/lib/postgresql/18/docker` sur l'image officielle (qui déclare `VOLUME /var/lib/postgresql`), la récupération se demande par un fichier `recovery.signal` vide et la cible se règle dans `postgresql.auto.conf`. Il n'y a plus de `recovery.conf`.

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

Avec un stockage S3, remplacez les montages `-v ..._pgarchive` par les variables `WALG_S3_PREFIX` / `AWS_*` du serveur source. Après la promotion, l'instance tourne sur une nouvelle timeline : faites immédiatement une nouvelle sauvegarde de base (`pg-backup once`). Pour les formes de production, utilisez le PITR de la plateforme : PostgreSQL géré (par ex. `gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>`) ou un cluster de récupération CloudNativePG (`deploy/helm/registerwerk/examples/cnpg-cluster.yaml`, en bas). Ces deux voies ne sont pas exercées par le script de ce dépôt ; réalisez votre propre exercice de restauration.

Les données validées après la cible de récupération (ou après le dernier segment archivé) sont perdues. Comparez `max(occurred_at)` avec l'heure de l'incident pour chiffrer la perte réelle, puis réconciliez avec les indexeurs de chaînes.

### 2b. Restaurer à partir de pg_dump (repli – RPO = dernier dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

**`scripts/pitr-drill.sh` exerce le chemin WAL-G (2a).** Il construit la même image `postgres-wal` que le overlay Compose, lance un serveur jetable avec `archive_mode=on`, prend une sauvegarde de base, insère des lignes avant et après une heure cible enregistrée, attend sans forcer de changement de segment que le segment de la dernière ligne soit archivé (l'échantillon de RPO), supprime le conteneur et son volume, restaure sauvegarde de base plus WAL à l'heure cible et vérifie que seules les lignes validées jusque-là existent. Il affiche le RPO et le RTO et dure environ 6 minutes avec `archive_timeout` 300 s. Avec `--record <backend-base-url> <operator-bearer-token>`, le résultat est enregistré comme entrée `SCENARIO_BASED` via `POST /api/v1/dora/resilience-tests` (désactivé par défaut).

### 2d. Lignes dans une partition DEFAULT

`token_transfer`, `blockchain_transaction` et `audit_event` sont partitionnées par mois. Une ligne dont `occurred_at` sort des partitions existantes (source d'indexeur mal horodatée, rattrapage historique) atterrit dans `<table>_default`, et Postgres ne peut plus créer la partition de ce mois. L'alerte `PartitionDefaultRowsPresent` (`registerwerk_partition_default_rows`) se déclenche. Scinder la partition par défaut pendant une fenêtre de maintenance (table, mois et dates sont des exemples) :

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

À répéter par mois et par table concernés. Aucun job de scission automatique n'existe (il nécessite une décision sur la fenêtre de maintenance).

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
# Trigger the verification: a POST (reading /actuator/health/auditChainVerificationService only
# reports the last verdict, it triggers nothing). Requires REGISTRY_ADMIN; scripts/dr-restore-drill.sh
# --verify-audit-chain shows the full login + XSRF-TOKEN flow.
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/api/v1/audit/chain/verify | jq .
```

Si le verdict est BROKEN : ne PAS reprendre l'exploitation. Escalader en incident CRITICAL ; la rupture de la chaîne de hachage doit être investiguée avant la reprise du registre. Un verdict BROKEN maintient `/actuator/health` à DOWN (la readiness n'est pas affectée) jusqu'à ce qu'une exécution ULTÉRIEURE soit valide ET que le verdict défaillant soit acquitté en double contrôle : `POST /api/v1/audit/verification/{id}/ack` (REGISTRY_ADMIN, step-up, second approbateur, motif `AUDIT_CHAIN_VERIFICATION_ACK`, `note` facultative) ; la page du journal d'audit du portail opérateur a le bouton correspondant. Le déclenchement se fait par POST ; lire `/actuator/health/auditChainVerificationService` ne montre que le dernier verdict.

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
Kong fonctionne sans base de données (DB-less) : toute sa configuration est `gateway/kong.yml`, chargée au démarrage (`KONG_DECLARATIVE_CONFIG`). Il n'y a aucune base à restaurer et aucun chemin d'écriture via l'API Admin ; `deck sync` ne s'applique donc pas. Restaurez le fichier depuis le contrôle de version, validez-le et recréez le conteneur :
```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml
docker compose up -d --force-recreate kong
```
Sur Kubernetes, le même fichier est livré sous `deploy/helm/registerwerk/files/kong.yml` (ConfigMap `registerwerk-kong-config`) : `helm upgrade`, puis redéployer Kong.

---

## 7. Liste de contrôle post-récupération
- [ ] État de Postgres : `pg_isready`
- [ ] État du backend : `/actuator/health` → UP
- [ ] Chaîne d'audit : `POST /api/v1/audit/chain/verify` renvoie `valid: true` (section 4), puis `/actuator/health` → UP
- [ ] Activité des indexeurs : `GET /api/v1/indexers` montre tous les indexeurs à jour, et aucune alerte `IndexerStaleCritical` / `IndexerStaleWarning` n'est active
- [ ] Dérive de la chaîne : confirmer qu'aucune ligne `chain_drift_event` ouverte n'a une gravité = CRITICAL
- [ ] Vérification des sanctions : confirmer qu'aucune ligne `screening_hit` ouverte n'a plus de 4 h
- [ ] Aperçu du registre : vérifier que les montants nominaux totaux correspondent à l'instantané pré-incident
- [ ] Si l'incident a été classé comme majeur, déposer les déclarations DORA dans les délais de l'acte délégué (vérifier le texte en vigueur, section 1)
